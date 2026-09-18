/**
 * 一個 Tool 長什麼樣子，以及怎麼把它掛到 Server 上。
 *
 * 宣告的部分（名字、說明、schema、annotations）會原樣出現在 `tools/list`；
 * `call` 不會——菜單上不寫怎麼做菜。
 *
 * `defineTool` 把一份宣告換成「會自己註冊的東西」。這樣做是為了型別：每個 Tool 的參數
 * 型別都不一樣，一個異質陣列裝不下它們，但裝得下一個統一的註冊函式。抹掉型別的地方就在
 * 這個檔案裡，一處；換來的是每個 Tool 的 `call` 都從自己的 schema 推出參數型別。
 */
import type {
  CallToolResult,
  McpServer,
  StandardSchemaWithJSON,
  ToolAnnotations,
  ToolCallback,
} from '@modelcontextprotocol/server';
import type { z } from 'zod';
import type { CallToolBody } from './result.js';

/** Zod schema 同時是 Standard Schema——SDK 靠後者驗參數並轉出 JSON Schema。 */
type ToolInputSchema = z.ZodObject & StandardSchemaWithJSON;

export interface ToolDefinition<Schema extends ToolInputSchema> {
  readonly name: string;
  readonly title?: string;
  readonly description: string;
  /** SDK 拿它驗參數，也把它轉成 JSON Schema 放進 `tools/list` 給模型看。 */
  readonly inputSchema: Schema;
  /**
   * Tool 對自己的宣告（唯讀、破壞性、冪等）。
   *
   * 規格要求 Client MUST 把這些當成不可信的自述；本 Server 也不因為它們改變行為，
   * 它們的作用是宣告與測試分區。
   */
  readonly annotations?: ToolAnnotations;
  /** 參數已經過 schema 驗證才會進來，所以這裡不必再驗一次形狀。 */
  readonly call: (args: z.output<Schema>) => Promise<CallToolBody>;
}

/** 抹掉參數型別之後剩下的：名字，以及怎麼把自己掛上去。 */
export interface RegisterableTool {
  readonly name: string;
  readonly register: (server: McpServer) => void;
}

export function defineTool<Schema extends ToolInputSchema>(
  tool: ToolDefinition<Schema>,
): RegisterableTool {
  const handler = async (args: z.output<Schema>): Promise<CallToolResult> => {
    const body = await tool.call(args);
    // 唯讀陣列複製成可變陣列：SDK 的結果型別要的是後者。
    return {
      content: [...body.content],
      structuredContent: body.structuredContent,
      isError: body.isError,
    };
  };

  return {
    name: tool.name,
    register: (server) => {
      server.registerTool<StandardSchemaWithJSON, Schema>(
        tool.name,
        {
          ...(tool.title === undefined ? {} : { title: tool.title }),
          description: tool.description,
          inputSchema: tool.inputSchema,
          ...(tool.annotations === undefined ? {} : { annotations: tool.annotations }),
        },
        // `ToolCallback<Schema>` 的參數型別是一個未解開的條件型別（Schema 還是變數），
        // 編譯器因此無法拿 handler 去比對它。handler 的參數型別由上面那一行寫定，
        // 和這裡要的是同一個東西，只差在編譯器算不出來。
        handler as ToolCallback<Schema>,
      );
    },
  };
}
