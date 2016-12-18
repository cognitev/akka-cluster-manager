package io.orkestra.cluster

import akka.actor.ActorRef

package object protocol {

  case class Register(member: ActorRef, role: String)
  case class RegisterInternal(member: ActorRef, role: String)

}
