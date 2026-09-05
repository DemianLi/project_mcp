package io.github.demianli.projectmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the MCP server.
 *
 * <p>Nothing is registered here. Tools are discovered by Spring AI's annotation
 * scanner from {@code @McpTool} methods on beans, so adding a Tool means adding an
 * annotated method — on a new component or on one that exists — and never editing this
 * class.
 *
 * <p>This process talks JSON-RPC over stdout. Anything else written to stdout
 * corrupts the stream, which is why the banner, the web server and console logging
 * are all switched off in {@code application.yml}.
 */
@SpringBootApplication
public class ProjectMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectMcpApplication.class, args);
    }
}
