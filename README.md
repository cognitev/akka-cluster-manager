# overview
this library is the product of over one year of runnign our own akka cluster (orkestra) in production. it started as a routing and registering library for actors and management of routers in the cluster but then it became a comprehensive tool internally for bootstrabing a new service with the least coding effort possible

# Installation
sbt dependency:
```sbt
resolvers += Some(Resolver.bintrayRepo("menacommere", "maven"))
libraryDependencies += "io.orkestra.ClusterManagement" % "clustermanagement_2.11" % "2.0"
```

# using the library
after including the library into your project, you need to provide some environmental variables to override the default configuration in the application.conf file like the seed node hostname and port and various other configurations.
a list of all available env variables that you can provide:

Env | Example | Description
--- | ------- | -----------
CLUSTER_NAME | orkestra | this is the name of the cluster and it should be the same in all your services
HOSTNAME | localhost | the host name of the service
NODE_EXTERNAL_PORT | 0 | netty.tcp.port (used if you are running your service inside docker)
NODE_INTERNAL_PORT | 0 | netty.tcp.bind-port
WEAKLY_UP_MEMBERS | on/off | not recommended to allow it in production
UNREACHABLE_DOWN_AFTER | 60 | number of seconds before marking an unreachable node down

you should also provide the roles for your service in application.conf like this:
```
akka.cluster.roles = ["access, users"]
```
in the boot of the application you have to instantiate a clustersocket actor which will join the cluster, act as a proxy for all the routers registered in every service and also listen to all cluster domain events
```scala
import io.orkestra.cluster.routing.ClusterListener
val clusterSocket: ActorRef = system.actorOf(ClusterListener.props("orkestra"), "cluster-socket")
```
for an actor to join the cluster, it's name should follow a naming convention as follows: role-handler
for example
```scala
val accessHandler: ActorRef = system.actorOf(AccessHandler.props(clusterSocket), "access-handler")
val usersHandler: ActorRef = system.actorOf(UsersHandler.props(clusterSocket), "users-handler")
```
users and access handlers should receive the clustersocket actor in their constructor to use it to get reference to any other actor in the cluster
let's say there is an ip service in the cluster and you would like to send a message to it to get ip info for a specific ip
```scala
import io.orkestra.cluster.routing.RouterRR.GetRoutee
private def getIPRoutee: Future[Option[ActorRef]] =
    (clusterSocket ? GetRoutee("ip)) map {
      case Routee(routee) =>
        routee
    }
```

there is also a management http server that you can spwn up during boot of your application that would allow you to view registered routees and
also delete a routee.
```scala
import io.orkestra.cluster.management.HttpManagementServer
new HttpManagementServer(clusterSocket, hostname, port).start
```

# License
the license is under MIT. see http://opensource.org/licenses/MIT
