/**
 * `delete_note`：多回合流程（MRTR）的範本。
 *
 * 破壞性的動作先問一次人再做。2026-07-28 沒有 Server 主動發請求這回事，改成兩回合：
 *
 *   1. Tool 回 `input_required`，附上「我要問什麼」（一個 elicitation）與一份封好的
 *      `requestState`，然後這一回合就結束了。
 *   2. Client 去問到答案，帶著 `inputResponses` 與原樣的 `requestState` 重送同一個請求。
 *      Tool 這次讀得到答案，才真的動手。
 *
 * 兩回合之間 Server 不記任何東西——狀態在 `requestState` 裡，而那份狀態經過 Client 的手，
 * 所以用 HMAC 封起來（見 `src/security/requestState.ts`）。它被改過或過期，SDK 會在進到
 * 這裡之前就擋掉；能讀到就是我們自己封的。
 */
import {
  acceptedContent,
  inputRequired,
  inputResponse,
  type ServerContext,
} from '@modelcontextprotocol/server';
import { z } from 'zod';
import { Scope } from '../../auth/scopes.js';
import { query } from '../../db/pool.js';
import { requestState, type RequestState } from '../../security/requestState.js';
import { databaseFailure } from '../dbFailure.js';
import { defineTool, type ToolOutcome } from '../definition.js';
import { Remedy } from '../remedy.js';
import { ok, failed } from '../result.js';

export const deleteNoteInput = z.object({
  id: z.int().positive().describe('The note id to delete.'),
});

/** 問出去的那一題長什麼樣子，也是收回來時用來驗答案的 schema。 */
const CONFIRMATION = z.object({
  confirm: z.boolean().describe('Delete the note?'),
});

/** `inputRequests` 與 `inputResponses` 對得起來靠這個鍵。 */
const CONFIRM_KEY = 'confirm';

export const deleteNote = defineTool({
  name: 'delete_note',
  title: 'Delete note',
  description: 'Deletes one note. Asks for confirmation first, then deletes on the retry.',
  inputSchema: deleteNoteInput,
  annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false },
  requiredScope: Scope.NotesDelete,
  call: async ({ id }, ctx): Promise<ToolOutcome> => {
    // 型別參數只是宣告「我封進去的是什麼」；真正的保證來自 codec 的 verify。
    const sealed = ctx.mcpReq.requestState<RequestState>();
    const answer = inputResponse(ctx.mcpReq.inputResponses, CONFIRM_KEY);

    // 第一回合：還沒問過。
    if (sealed === undefined || answer.kind === 'missing') {
      return askFirst(id, ctx);
    }

    // 第二回合。先看人怎麼回答。
    if (answer.kind !== 'elicit' || answer.action !== 'accept') {
      return failed(Remedy.AskOperator, `Deleting note ${id} was not confirmed (${answer.kind === 'elicit' ? answer.action : answer.kind}).`);
    }
    const content = acceptedContent(ctx.mcpReq.inputResponses, CONFIRM_KEY, CONFIRMATION);
    if (content === undefined || !content.confirm) {
      return failed(Remedy.AskOperator, `Deleting note ${id} was declined.`);
    }

    // 參數是這一回合才送來的，可以跟上一回合封好的不一樣。以封好的那份為準。
    if (sealed.tool !== 'delete_note' || sealed.noteId !== id) {
      return failed(Remedy.FixRequest, 'The confirmation does not belong to this request.');
    }

    try {
      const rows = await query<{ id: string }>('delete from notes where id = $1 returning id', [id]);
      // 冪等：期間被別人刪掉了也算達成目的，不是失敗。
      return ok({ id, deleted: rows.length > 0 });
    } catch (cause) {
      return databaseFailure(cause);
    }
  },
});

/** 第一回合：確認這筆存在，然後把問題與狀態一起送出去。 */
async function askFirst(id: number, ctx: ServerContext): Promise<ToolOutcome> {
  let body: string;
  try {
    const rows = await query<{ body: string }>('select body from notes where id = $1', [id]);
    const row = rows[0];
    if (row === undefined) {
      // 換一個 id 就會成功，所以是 FIX_REQUEST。這裡就回掉，不必浪費一次確認。
      return failed(Remedy.FixRequest, `No note with id ${id}.`);
    }
    body = row.body;
  } catch (cause) {
    return databaseFailure(cause);
  }

  return inputRequired({
    inputRequests: {
      [CONFIRM_KEY]: inputRequired.elicit({
        message: `Delete note ${id}? It says: ${JSON.stringify(body)}`,
        requestedSchema: CONFIRMATION,
      }),
    },
    requestState: await requestState.mint({ tool: 'delete_note', noteId: id }, ctx),
  });
}
