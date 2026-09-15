package io.github.demianli.projectmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the MCP server. Tools are discovered by Spring AI from
 * {@code @McpTool} methods on beans. stdout carries JSON-RPC; see application.yml
 * for why the banner, web server, and console logging are off.
 */
@SpringBootApplication
public class ProjectMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectMcpApplication.class, args);
    }
}
