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
SEED_NODE_HOSTNAME | localhost | seed node hostname
SEED_NODE_PORT | 2551 | seed node port
SEED2_NODE_HOSTNAME | localhost | seed node 2 hostname
SEED2_NODE_PORT | 2552 | seed node 2 port
WEAKLY_UP_MEMBERS | on/off | not recommended to allow it in production
UNREACHABLE_DOWN_AFTER | 60 | number of seconds before marking an unreachable node down

you should also provide the roles for your service in application.conf like this:
```
akka.cluster.roles = ["access, users"]
```
in the boot of the application you have to instantiate a clustersocket actor which will make your service join the cluster, act as a proxy for all the routers registered in every service and also listen to all cluster domain events
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

seed node should have an extra role called **lighthouse** which will distinguish it inside the cluster preventing any other node from downing it.
this is because the seed node has fixed ip which will prohibit its reincarnations from joining the cluster again in case it was marked down (in which case the only solution will be restarting the whole cluster).

there is also a management http server that you can spawn up during boot of your application that would allow you to view registered routees and
also delete a routee.
```scala
import io.orkestra.cluster.management.HttpManagementServer
new HttpManagementServer(clusterSocket, 127.0.0.1, 8888).start
```
this will start a server on 127.0.0.1:8888 which u can use as following:
```
curl http://127.0.0.1:8888/orkestra/routers
```
this will get u all the registered routers and to get the routees in a specific router u can use:
```
curl http://127.0.0.1:8888/orkestra/routers/<router_name>
```
u can also delete a specific routee from a router using the following:
```
curl -XDEL http://127.0.0.1:8888/orkestra/routers/<router_name>/<routee_path>
```

p.s. this http layer will get you the registered routees from the library POV not akka's POV which was the main drive for its addition to
check inconsistencies in the library and better debugging.

# License
the license is under MIT. see http://opensource.org/licenses/MIT
