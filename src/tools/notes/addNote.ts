/**
 * `add_note`：寫入的範本。
 *
 * 只有一句 SQL 也走 `withTransaction`，是為了示範多步驟寫入該用的形狀——自己
 * `connect()` 然後忘記 `release()` 是連線池最常見的死法。
 */
import { z } from 'zod';
import { withTransaction } from '../../db/pool.js';
import { databaseFailure } from '../dbFailure.js';
import { defineTool } from '../definition.js';
import { ok } from '../result.js';

export const addNoteInput = z.object({
  body: z.string().trim().min(1).max(2000).describe('The note text.'),
});

export const addNote = defineTool({
  name: 'add_note',
  title: 'Add note',
  description: 'Writes one note and returns it.',
  inputSchema: addNoteInput,
  // 不是唯讀，也不冪等：同樣的參數呼叫兩次會有兩筆。
  annotations: { readOnlyHint: false, idempotentHint: false, openWorldHint: false },
  call: async ({ body }) => {
    try {
      const row = await withTransaction(async (client) => {
        const result = await client.query<{ id: string; created_at: Date }>(
          'insert into notes (body) values ($1) returning id, created_at',
          [body],
        );
        return result.rows[0];
      });
      if (row === undefined) {
        throw new Error('insert returned no row');
      }
      return ok({ id: Number(row.id), body, createdAt: row.created_at.toISOString() });
    } catch (cause) {
      return databaseFailure(cause);
    }
  },
});
