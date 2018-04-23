package cluster.persistence.query;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.sharding.ClusterSharding;
import akka.cluster.sharding.ClusterShardingSettings;
import akka.cluster.singleton.ClusterSingletonManager;
import akka.cluster.singleton.ClusterSingletonManagerSettings;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class Runner {
    public static void main(String[] args) {
        List<ActorSystem> actorSystems;

        if (args.length == 0) {
            actorSystems = startupClusterNodes(Arrays.asList("2551", "2552", "0"));
        } else {
            actorSystems = startupClusterNodes(Arrays.asList(args));
        }

        hitEnterToStop();

        for (ActorSystem actorSystem : actorSystems) {
            Cluster cluster = Cluster.get(actorSystem);
            cluster.leave(cluster.selfAddress());
        }

        System.exit(0);
    }

    private static List<ActorSystem> startupClusterNodes(List<String> ports) {
        System.out.printf("Start cluster on port(s) %s%n", ports);
        List<ActorSystem> actorSystems = new ArrayList<>();

        for (String port : ports) {
            ActorSystem actorSystem = ActorSystem.create("persistence", setupClusterNodeConfig(port));

            actorSystem.actorOf(ClusterListenerActor.props(), "clusterListener");
//            actorSystem.actorOf(ReadSideProcessorIdsActor.props(), "pullJournalIds");

            createClusterSingletonManagerActor(actorSystem);

            actorSystems.add(actorSystem);
        }

        return actorSystems;
    }

    private static Config setupClusterNodeConfig(String port) {
        return ConfigFactory.parseString(
                String.format("akka.remote.netty.tcp.port=%s%n", port) +
                        String.format("akka.remote.artery.canonical.port=%s%n", port))
                .withFallback(ConfigFactory.load()
                );
    }

    private static void createClusterSingletonManagerActor(ActorSystem actorSystem) {
        ClusterSingletonManagerSettings settings = ClusterSingletonManagerSettings.create(actorSystem).withRole("read-side");
        Props clusterSingletonManagerProps = ClusterSingletonManager.props(
                ClusterSingletonActor.props(setupClusterSharding(actorSystem)),
                PoisonPill.getInstance(),
                settings
        );

        actorSystem.actorOf(clusterSingletonManagerProps, "clusterSingletonManager");
    }

    private static ActorRef setupClusterSharding(ActorSystem actorSystem) {
        ClusterShardingSettings settings = ClusterShardingSettings.create(actorSystem).withRole("read-side");
        return ClusterSharding.get(actorSystem).start(
                "readSideProcessor",
                ReadSideProcessorActor.props(),
                settings,
                ReadSideProcessorActor.messageExtractor()
        );
    }

    private static void hitEnterToStop() {
        System.out.println("Hit Enter to stop");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            reader.readLine();
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
