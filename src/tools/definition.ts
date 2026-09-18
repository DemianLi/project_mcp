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
import {
  isInputRequiredResult,
  type CallToolResult,
  type InputRequiredResult,
  type McpServer,
  type ServerContext,
  type StandardSchemaWithJSON,
  type ToolAnnotations,
  type ToolCallback,
} from '@modelcontextprotocol/server';
import type { z } from 'zod';
import { audit } from '../audit.js';
import { agencyOf } from '../auth/context.js';
import { trackInFlight } from '../lifecycle.js';
import type { CallToolBody } from './result.js';

/** Zod schema 同時是 Standard Schema——SDK 靠後者驗參數並轉出 JSON Schema。 */
type ToolInputSchema = z.ZodObject & StandardSchemaWithJSON;

/**
 * 一次呼叫的結局：做完了，或是還差一份輸入。
 *
 * `InputRequiredResult` 是 2026-07-28 的多回合流程（MRTR）：Tool 先回一份「我需要什麼」，
 * Client 去問到答案，再帶著 `inputResponses` 重送同一個請求。用 SDK 的 `inputRequired()`
 * 建它。
 */
export type ToolOutcome = CallToolBody | InputRequiredResult;

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
  /**
   * 參數已經過 schema 驗證才會進來，所以這裡不必再驗一次形狀。
   *
   * `ctx` 是這一回合的上下文：`ctx.mcpReq.inputResponses` 是 Client 帶回來的答案，
   * `ctx.mcpReq.requestState()` 是我們上一回合自己封好的狀態。不做多回合的 Tool 用不到它。
   */
  readonly call: (args: z.output<Schema>, ctx: ServerContext) => Promise<ToolOutcome>;
}

/** 抹掉參數型別之後剩下的：名字，以及怎麼把自己掛上去。 */
export interface RegisterableTool {
  readonly name: string;
  readonly register: (server: McpServer) => void;
}

export function defineTool<Schema extends ToolInputSchema>(
  tool: ToolDefinition<Schema>,
): RegisterableTool {
  const handler = async (
    args: z.output<Schema>,
    ctx: ServerContext,
  ): Promise<CallToolResult | InputRequiredResult> => {
    // 記在帳上，關機才知道要等誰。
    const started = Date.now();
    let outcome: ToolOutcome;
    try {
      outcome = await trackInFlight(() => tool.call(args, ctx));
    } catch (cause) {
      // Tool 自己沒接住的例外也要留下紀錄，否則稽核上會看到一個沒有結果的呼叫。
      audit({ agency: agencyOf(ctx), tool: tool.name, outcome: 'threw', ms: Date.now() - started });
      throw cause;
    }
    audit({
      agency: agencyOf(ctx),
      tool: tool.name,
      outcome: isInputRequiredResult(outcome) ? 'input_required' : outcome.isError ? 'failed' : 'ok',
      ms: Date.now() - started,
    });
    if (isInputRequiredResult(outcome)) {
      // 多回合的結果原樣送出去：`resultType: 'input_required'` 是它的辨識欄位。
      return outcome;
    }
    // 唯讀陣列複製成可變陣列：SDK 的結果型別要的是後者。
    return {
      content: [...outcome.content],
      structuredContent: outcome.structuredContent,
      isError: outcome.isError,
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
