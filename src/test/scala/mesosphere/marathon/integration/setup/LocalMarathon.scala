package mesosphere.marathon
package integration.setup

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import akka.Done
import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import akka.stream.Materializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.health.{ HealthCheck, MarathonHttpHealthCheck }
import mesosphere.marathon.integration.facades.{ ITDeploymentResult, ITEnrichedTask, MarathonFacade, MesosFacade }
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.{ AppDefinition, Container, DockerVolume, PathId }
import mesosphere.marathon.test.ExitDisabledTest
import mesosphere.marathon.util.{ Lock, Retry }
import mesosphere.util.PortAllocator
import org.apache.commons.io.FileUtils
import org.apache.mesos.Protos
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Suite }
import org.slf4j.LoggerFactory
import play.api.libs.json.{ JsString, Json }

import scala.annotation.tailrec
import scala.async.Async.{ async, await }
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.sys.process.{ Process, ProcessLogger }
import scala.util.Try

case class LocalMarathon(
    autoStart: Boolean = true,
    masterUrl: String,
    zkUrl: String,
    conf: Map[String, String] = Map.empty,
    mainClass: String = "mesosphere.marathon.Main",
    logStdout: Boolean = false)(implicit
  system: ActorSystem,
    mat: Materializer,
    ctx: ExecutionContext,
    scheduler: Scheduler) extends AutoCloseable {

  lazy val httpPort = PortAllocator.ephemeralPort()
  private val logger = LoggerFactory.getLogger(s"LocalMarathon:$httpPort")

  private val workDir = {
    val f = Files.createTempDirectory(s"marathon-$httpPort").toFile
    f.deleteOnExit()
    f
  }
  private def write(dir: File, fileName: String, content: String): String = {
    val file = File.createTempFile(fileName, "", dir)
    file.deleteOnExit()
    FileUtils.write(file, content)
    file.setReadable(true)
    file.getAbsolutePath
  }

  private val secretPath = write(workDir, fileName = "marathon-secret", content = "secret1")

  val config = Map(
    "master" -> masterUrl,
    "mesos_authentication_principal" -> "principal",
    "mesos_role" -> "foo",
    "http_port" -> httpPort.toString,
    "zk" -> zkUrl,
    "mesos_authentication_secret_file" -> s"$secretPath",
    "event_subscriber" -> "http_callback",
    "access_control_allow_origin" -> "*",
    "reconciliation_initial_delay" -> "600000",
    "min_revive_offers_interval" -> "100"
  ) ++ conf

  val args = config.flatMap { case (k, v) => Seq(s"--$k", v) }(collection.breakOut)

  private var marathon = Option.empty[Process]

  if (autoStart) {
    start()
  }

  // it'd be great to be able to execute in memory, but we can't due to GuiceFilter using a static :(
  private val processBuilder = {
    val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val cp = sys.props.getOrElse("java.class.path", "target/classes")
    val memSettings = s"-Xmx${Runtime.getRuntime.maxMemory()}"
    val cmd = Seq(java, memSettings, "-classpath", cp, mainClass) ++ args
    Process(cmd, workDir, sys.env.toSeq: _*)
  }

  private def create(): Process = {
    if (logStdout) {
      processBuilder.run(ProcessLogger(logger.info, logger.warn))
    } else {
      processBuilder.run()
    }
  }

  def start(): Future[Done] = {
    if (marathon.isEmpty) {
      marathon = Some(create())
      val future = Retry(s"marathon-$httpPort", Int.MaxValue, 1.milli, 5.seconds) {
        async {
          val result = await(Http(system).singleRequest(Get(s"http://localhost:$httpPort/v2/leader")))
          if (result.status.isSuccess()) { // linter:ignore //async/await
            Done
          } else {
            throw new Exception("Marathon not ready yet.")
          }
        }
      }
      future.onFailure { case _ => marathon = Option.empty[Process] }
      future
    } else {
      Future.successful(Done)
    }
  }

  def stop(): Unit = {
    marathon.foreach(_.destroy())
    marathon = Option.empty[Process]
  }

  override def close(): Unit = {
    stop()
    Try(FileUtils.deleteDirectory(workDir))
  }
}

trait MarathonTest extends BeforeAndAfterAll { this: Suite with StrictLogging with ScalaFutures =>
  def marathonUrl: String
  def marathon: MarathonFacade
  def mesos: MesosFacade
  val testBasePath: PathId

  private val appProxyIds = Lock(mutable.ListBuffer.empty[String])

  import UpdateEventsHelper._
  implicit val system: ActorSystem
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  implicit val scheduler: Scheduler

  protected val events = new ConcurrentLinkedQueue[CallbackEvent]()
  protected val healthChecks = Lock(mutable.ListBuffer.empty[IntegrationHealthCheck])
  protected[setup] lazy val callbackEndpoint = {
    val route = {
      import akka.http.scaladsl.server.Directives._
      val mapper = new ObjectMapper() with ScalaObjectMapper
      mapper.registerModule(DefaultScalaModule)

      implicit val unmarshal = new FromRequestUnmarshaller[Map[String, Any]] {
        override def apply(value: HttpRequest)(implicit ec: ExecutionContext, materializer: Materializer): Future[Map[String, Any]] = {
          value.entity.toStrict(5.seconds)(materializer).map { entity =>
            mapper.readValue[Map[String, Any]](entity.data.utf8String)
          }(ec)
        }
      }

      (post & entity(as[Map[String, Any]])) { event =>
        val kind = event.get("eventType") match {
          case Some(JsString(s)) => s
          case Some(s) => s.toString
          case None => "unknown"
        }
        logger.info(s"Received callback event: $kind with props $event")
        events.add(CallbackEvent(kind, event))
        complete(HttpResponse(status = StatusCodes.OK))
      } ~ get {
        path("health" / Segments) { uriPath =>
          import PathId._
          val (path, remaining) = uriPath.splitAt(uriPath.size - 2)
          val (versionId, port) = (remaining.head, remaining.tail.head.toInt)
          val appId = path.mkString("/").toRootPath
          def instance = healthChecks(_.find { c => c.appId == appId && c.versionId == versionId && c.port == port })
          def definition = healthChecks(_.find { c => c.appId == appId && c.versionId == versionId && c.port == 0 })
          val state = instance.orElse(definition).fold(true)(_.healthy)
          if (state) {
            complete(HttpResponse(status = StatusCodes.OK))
          } else {
            complete(HttpResponse(status = StatusCodes.InternalServerError))
          }
        } ~ path(Remaining) { path =>
          require(false, s"$path was unmatched!")
          complete(HttpResponse(status = StatusCodes.InternalServerError))
        }
      }
    }
    val port = PortAllocator.ephemeralPort()
    val server = Http().bindAndHandle(route, "localhost", port).futureValue
    marathon.subscribe(s"http://localhost:$port")
    logger.info(s"Listening for events on $port")
    server
  }

  abstract override def afterAll(): Unit = {
    Try(marathon.unsubscribe(s"http://localhost:${callbackEndpoint.localAddress.getPort}"))
    callbackEndpoint.unbind().futureValue
    killAppProxies()
    super.afterAll()
  }

  private def killAppProxies(): Unit = {
    val PIDRE = """^\s*(\d+)\s+(.*)$""".r
    val allJavaIds = Process("jps -lv").!!.split("\n")
    val pids = allJavaIds.collect {
      case PIDRE(pid, exec) if appProxyIds(_.exists(exec.contains)) => pid
    }
    if (pids.nonEmpty) {
      Process(s"kill -9 ${pids.mkString(" ")}").run().exitValue()
    }
  }

  implicit class PathIdTestHelper(path: String) {
    def toRootTestPath: PathId = testBasePath.append(path).canonicalPath()
    def toTestPath: PathId = testBasePath.append(path)
  }

  val appProxyMainInvocationImpl: String = {
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[AppMock].getName
    val id = UUID.randomUUID.toString
    appProxyIds(_ += id)
    s"""$javaExecutable -Xmx64m -DappProxyId=$id -classpath $classPath $main"""
  }

  lazy val appProxyHealthChecks = Set(
    MarathonHttpHealthCheck(gracePeriod = 5.second, interval = 0.5.second, maxConsecutiveFailures = 2))

  def appProxy(appId: PathId, versionId: String, instances: Int,
    withHealth: Boolean = true, dependencies: Set[PathId] = Set.empty): AppDefinition = {

    val appProxyMainInvocation: String = {
      val file = File.createTempFile("appProxy", ".sh")
      file.deleteOnExit()

      FileUtils.write(
        file,
        s"""#!/bin/sh
            |set -x
            |exec $appProxyMainInvocationImpl $$*""".stripMargin)
      file.setExecutable(true)

      file.getAbsolutePath
    }
    val cmd = Some(s"""echo APP PROXY $$MESOS_TASK_ID RUNNING; $appProxyMainInvocation """ +
      s"""$appId $versionId http://127.0.0.1:${callbackEndpoint.localAddress.getPort}/health$appId/$versionId""")

    AppDefinition(
      id = appId,
      cmd = cmd,
      executor = "//cmd",
      instances = instances,
      resources = Resources(cpus = 0.5, mem = 128.0),
      healthChecks = if (withHealth) appProxyHealthChecks else Set.empty[HealthCheck],
      dependencies = dependencies
    )
  }

  def dockerAppProxy(appId: PathId, versionId: String, instances: Int, withHealth: Boolean = true, dependencies: Set[PathId] = Set.empty): AppDefinition = {
    val targetDirs = sys.env.getOrElse("TARGET_DIRS", "/marathon")
    val cmd = Some(s"""bash -c 'echo APP PROXY $$MESOS_TASK_ID RUNNING; $appProxyMainInvocationImpl $appId $versionId http://$$HOST:${PortAllocator.ephemeralPort()}/health$appId/$versionId'""")
    AppDefinition(
      id = appId,
      cmd = cmd,
      container = Some(Container.Docker(
        image = s"""marathon-buildbase:${sys.env.getOrElse("BUILD_ID", "test")}""",
        network = Some(Protos.ContainerInfo.DockerInfo.Network.HOST),
        volumes = collection.immutable.Seq(
          new DockerVolume(hostPath = sys.env.getOrElse("IVY2_DIR", "/root/.ivy2"), containerPath = "/root/.ivy2", mode = Protos.Volume.Mode.RO),
          new DockerVolume(hostPath = sys.env.getOrElse("SBT_DIR", "/root/.sbt"), containerPath = "/root/.sbt", mode = Protos.Volume.Mode.RO),
          new DockerVolume(hostPath = sys.env.getOrElse("SBT_DIR", "/root/.sbt"), containerPath = "/root/.sbt", mode = Protos.Volume.Mode.RO),
          new DockerVolume(hostPath = s"""$targetDirs/main""", containerPath = "/marathon/target", mode = Protos.Volume.Mode.RO),
          new DockerVolume(hostPath = s"""$targetDirs/project""", containerPath = "/marathon/project/target", mode = Protos.Volume.Mode.RO)
        )
      )),
      instances = instances,
      resources = Resources(cpus = 0.5, mem = 128.0),
      healthChecks = if (withHealth) appProxyHealthChecks else Set.empty[HealthCheck],
      dependencies = dependencies
    )
  }

  def waitForTasks(appId: PathId, num: Int, maxWait: FiniteDuration = 30.seconds): List[ITEnrichedTask] = {
    def checkTasks: Option[List[ITEnrichedTask]] = {
      val tasks = Try(marathon.tasks(appId)).map(_.value).getOrElse(Nil).filter(_.launched)
      if (tasks.size == num) Some(tasks) else None
    }
    WaitTestSupport.waitFor(s"$num tasks to launch", maxWait)(checkTasks)
  }

  def cleanUp(withSubscribers: Boolean = false): Unit = {
    logger.info("Starting to CLEAN UP !!!!!!!!!!")
    events.clear()

    val deleteResult: RestResult[ITDeploymentResult] = marathon.deleteGroup(testBasePath, force = true)
    if (deleteResult.code != 404) {
      waitForChange(deleteResult)
    }

    WaitTestSupport.waitUntil("clean slate in Mesos", 45.seconds) {
      mesos.state.value.agents.map { agent =>
        val empty = agent.usedResources.isEmpty && agent.reservedResourcesByRole.isEmpty
        if (!empty) {
          import mesosphere.marathon.integration.facades.MesosFormats._
          logger.info(
            "Waiting for blank slate Mesos...\n \"used_resources\": "
              + Json.prettyPrint(Json.toJson(agent.usedResources)) + "\n \"reserved_resources\": "
              + Json.prettyPrint(Json.toJson(agent.reservedResourcesByRole))
          )
        }
        empty
      }.fold(true) { (acc, next) => if (!next) next else acc }
    }

    val apps = marathon.listAppsInBaseGroup
    require(apps.value.isEmpty, s"apps weren't empty: ${apps.entityPrettyJsonString}")
    val groups = marathon.listGroupsInBaseGroup
    require(groups.value.isEmpty, s"groups weren't empty: ${groups.entityPrettyJsonString}")
    events.clear()
    healthChecks(_.clear())
    killAppProxies()
    if (withSubscribers) marathon.listSubscribers.value.urls.foreach(marathon.unsubscribe)

    logger.info("CLEAN UP finished !!!!!!!!!")
  }

  def appProxyCheck(appId: PathId, versionId: String, state: Boolean): IntegrationHealthCheck = {
    val check = new IntegrationHealthCheck(appId, versionId, 0, state)
    healthChecks { checks =>
      checks.filter(c => c.appId == appId && c.versionId == versionId).foreach(checks -= _)
      checks += check
    }
    check
  }

  def waitForHealthCheck(check: IntegrationHealthCheck, maxWait: FiniteDuration = 30.seconds) = {
    WaitTestSupport.waitUntil("Health check to get queried", maxWait) { check.pinged }
  }

  def waitForDeploymentId(deploymentId: String, maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    waitForEventWith("deployment_success", _.id == deploymentId, maxWait)
  }

  def waitForStatusUpdates(kinds: String*) = kinds.foreach { kind =>
    waitForEventWith("status_update_event", _.taskStatus == kind)
  }

  def waitForEvent(
    kind: String,
    maxWait: FiniteDuration = 60.seconds): CallbackEvent =
    waitForEventWith(kind, _ => true, maxWait)

  def waitForEventWith(
    kind: String,
    fn: CallbackEvent => Boolean, maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    waitForEventMatching(s"event $kind to arrive", maxWait) { event =>
      event.eventType == kind && fn(event)
    }
  }

  def waitForEventMatching(
    description: String,
    maxWait: FiniteDuration = 30.seconds)(fn: CallbackEvent => Boolean): CallbackEvent = {
    @tailrec
    def nextEvent: Option[CallbackEvent] = if (events.isEmpty) None else {
      val event = events.poll()
      if (fn(event)) Some(event) else nextEvent
    }
    WaitTestSupport.waitFor(description, maxWait)(nextEvent)
  }

  /**
    * Wait for the events of the given kinds (=types).
    */
  def waitForEvents(kinds: String*)(maxWait: FiniteDuration = 30.seconds): Map[String, Seq[CallbackEvent]] = {

    val deadline = maxWait.fromNow

    /** Receive the events for the given kinds (duplicates allowed) in any order. */
    val receivedEventsForKinds: Seq[CallbackEvent] = {
      var eventsToWaitFor = kinds
      val receivedEvents = Vector.newBuilder[CallbackEvent]

      while (eventsToWaitFor.nonEmpty) {
        val event = waitForEventMatching(s"event $eventsToWaitFor to arrive", deadline.timeLeft) { event =>
          eventsToWaitFor.contains(event.eventType)
        }
        receivedEvents += event

        // Remove received event kind. Only remove one element for duplicates.
        val kindIndex = eventsToWaitFor.indexWhere(_ == event.eventType)
        assert(kindIndex >= 0)
        eventsToWaitFor = eventsToWaitFor.patch(kindIndex, Nil, 1)
      }

      receivedEvents.result()
    }

    receivedEventsForKinds.groupBy(_.eventType)
  }

  def waitForChange(change: RestResult[ITDeploymentResult], maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    waitForDeploymentId(change.value.deploymentId, maxWait)
  }
}

trait LocalMarathonTest
    extends ExitDisabledTest
    with MarathonTest
    with BeforeAndAfterAll
    with ScalaFutures {
  this: Suite with StrictLogging with ZookeeperServerTest with MesosTest =>

  val marathonArgs = Map.empty[String, String]

  lazy val marathonServer = LocalMarathon(autoStart = false, masterUrl = mesosMasterUrl,
    zkUrl = s"zk://${zkServer.connectUri}/marathon",
    conf = marathonArgs)
  lazy val marathonUrl = s"http://localhost:${marathonServer.httpPort}"

  val testBasePath: PathId = PathId("/")
  lazy val marathon = new MarathonFacade(marathonUrl, testBasePath)
  lazy val appMock: AppMockFacade = new AppMockFacade()

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    marathonServer.start().futureValue(Timeout(60.seconds))
    callbackEndpoint
  }

  abstract override def afterAll(): Unit = {
    marathonServer.close()
    super.afterAll()
  }
}

trait EmbeddedMarathonTest extends ZookeeperServerTest with MesosLocalTest with LocalMarathonTest {
  this: Suite with StrictLogging =>
}

trait EmbeddedMarathonMesosClusterTest extends ZookeeperServerTest with MesosClusterTest with LocalMarathonTest {
  this: Suite with StrictLogging =>
}

trait MarathonClusterTest extends ZookeeperServerTest with MesosLocalTest with LocalMarathonTest {
  this: Suite with StrictLogging =>

  val numAdditionalMarathons = 2
  lazy val additionalMarathons = 0.until(numAdditionalMarathons).map { _ =>
    LocalMarathon(autoStart = false, masterUrl = mesosMasterUrl,
      zkUrl = s"zk://${zkServer.connectUri}/marathon",
      conf = marathonArgs)
  }
  lazy val marathonFacades = marathon +: additionalMarathons.map { m =>
    new MarathonFacade(s"http://localhost:${m.httpPort}", testBasePath)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    Future.sequence(additionalMarathons.map(_.start())).futureValue(Timeout(60.seconds))
  }

  override def afterAll(): Unit = {
    additionalMarathons.foreach(_.close())
    super.afterAll()
  }
}