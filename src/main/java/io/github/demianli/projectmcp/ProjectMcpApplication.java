package io.github.demianli.projectmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server 的進入點。Spring AI 從 bean 上的 {@code @McpTool} 方法找出 Tools。
 * stdout 承載 JSON-RPC；banner、web server 與 console log 為何關閉，見 application.yml。
 */
@SpringBootApplication
public class ProjectMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectMcpApplication.class, args);
    }
}
