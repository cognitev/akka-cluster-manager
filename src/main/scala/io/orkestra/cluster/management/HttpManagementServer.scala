package io.orkestra.cluster.management

import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.actor.{ActorSystem, ActorRef}
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import io.orkestra.cluster.protocol.Response.{Failure, Success}
import io.orkestra.cluster.routing.ClusterListener._
import akka.pattern.ask
import play.api.libs.json.Json
import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.concurrent.duration._

class HttpManagementServer(clusterListener: ActorRef, hostName: String = "127.0.0.1", port: Int = 33333)(
    implicit
    val system:                ActorSystem,
    implicit val materializer: Materializer,
    implicit val executer:     ExecutionContext
) {

  import PlayJsonSupport._

  def handleOrkestraRequest(req: ManagementReguest) =
    (clusterListener ? req)(3.seconds).map {
      case res: Success =>
        res.httpStatusCode -> res.asJson
      case res: Failure =>
        res.httpStatusCode -> res.asJson
    }

  def orkestraRoutes =
    pathPrefix("orkestra" / "routers") {
      pathEndOrSingleSlash {
        get {
          complete(handleOrkestraRequest(GetRouters))
        }
      } ~
        path(Segment ~ Slash.?) { role =>
          get {
            complete(handleOrkestraRequest(GetRouter(role)))
          }
        } ~
        path(Segment / Remaining ~ Slash.?) { (role, routeePath) =>
          delete {
            complete(handleOrkestraRequest(DeleteRoutee(role, routeePath)))
          }
        }
    }

  private val bindingFuture = new AtomicReference[Future[Http.ServerBinding]]()

  def start() = {
    val serverBindingPromise = Promise[Http.ServerBinding]()
    if (bindingFuture.compareAndSet(null, serverBindingPromise.future)) {
      Http().bindAndHandle(orkestraRoutes, hostName, port)
      println(Console.CYAN + s"cluster http management server online at http://${hostName}:${port}/" + Console.WHITE)
    }
  }

  def shutdown =
    if (bindingFuture.get() == null) {
      Future(Done)
    } else {
      val stopFuture = bindingFuture.get().flatMap(_.unbind()).map(_ => Done)
      bindingFuture.set(null)
      stopFuture
    }

}
