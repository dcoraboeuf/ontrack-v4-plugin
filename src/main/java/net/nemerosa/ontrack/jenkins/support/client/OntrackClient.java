package net.nemerosa.ontrack.jenkins.support.client;

import com.fasterxml.jackson.databind.JsonNode;
import net.nemerosa.ontrack.jenkins.OntrackConfiguration;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;

import static net.nemerosa.ontrack.jenkins.OntrackConfiguration.getOntrackConfiguration;

public final class OntrackClient {

    /**
     * Performs call on a branch.
     *
     * @param project Name of the project
     * @param branch  Name of the branch
     */
    public static OntrackResource forBranch(PrintStream logger, String project, String branch) throws IOException {
        return forResource(logger, "structure/entity/branch/%s/%s", project, branch);
    }

    private static OntrackResource forResource(PrintStream logger, String path, Object... parameters) throws IOException {

        // Gets the configuration
        OntrackConfiguration configuration = getOntrackConfiguration();

        // Gets the URL to the resource
        URL url = new URL(
                StringUtils.stripEnd(configuration.getOntrackUrl(), "/")
                        + "/"
                        + StringUtils.stripStart(String.format(path, parameters), "/")
        );
        logger.format("[ontrack] Getting resource from %s%n", url);

        // Gets the connector
        OntrackConnector connector = new OntrackConnector(url);

        // Gets the resource as JSON
        JsonNode resource = connector.get();

        // Creates the resource
        return new OntrackResource(resource);
    }

    private OntrackClient() {
    }
}
