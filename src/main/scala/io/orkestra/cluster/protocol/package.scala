package io.orkestra.cluster

import akka.actor.ActorRef
import akka.http.scaladsl.model.{StatusCodes, StatusCode}
import play.api.libs.json.{JsString, Format, Json, JsValue}

package object protocol {

  case class Register(member: ActorRef, role: String)
  case class RegisterInternal(member: ActorRef, role: String)

  sealed trait Response {
    def asJson: JsValue
    def httpStatusCode: StatusCode
  }

  object Response {

    trait Success extends Response {
      override val httpStatusCode: StatusCode = StatusCodes.OK
    }

    object Success {

      case class Router(name: String, routees: List[String]) extends Success {
        override val asJson = Json.toJson(this)
      }
      object Router {
        implicit val fmt: Format[Router] = Json.format[Router]
      }

      case class Routers(routers: Iterable[JsValue]) extends Success {
        override val asJson = Json.toJson(routers)
      }

      case class RouteeDeleted(role: String, path: String) extends Success {
        override val asJson = JsString(s"routee: $path with role: $role successfully deleted")
      }

    }

    trait Failure extends Response

    object Failure {
      case class RouterNotFound(role: String) extends Failure {
        override val httpStatusCode: StatusCode = StatusCodes.NotFound
        override val asJson: JsValue = Json.obj("error" -> s"router with role: $role not found")
      }
    }
  }

}
