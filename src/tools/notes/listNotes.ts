/**
 * `list_notes`：讀一張表的範本。
 *
 * 一次 `query` 就夠了。失敗的分類交給 `databaseFailure`，Tool 自己只負責「查什麼」。
 */
import { z } from 'zod';
import { query } from '../../db/pool.js';
import { databaseFailure } from '../dbFailure.js';
import { defineTool } from '../definition.js';
import { ok } from '../result.js';

export const listNotesInput = z.object({
  limit: z.int().min(1).max(200).default(20).describe('How many notes to return, newest first.'),
});

interface NoteRow {
  readonly id: string;
  readonly body: string;
  readonly created_at: Date;
}

export const listNotes = defineTool({
  name: 'list_notes',
  title: 'List notes',
  description: 'Notes, newest first.',
  inputSchema: listNotesInput,
  annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
  call: async ({ limit }) => {
    try {
      // bigint 在 pg 預設會讀成字串，因為 JS 的 number 裝不下它的全部範圍。
      const rows = await query<NoteRow>(
        'select id, body, created_at from notes order by created_at desc, id desc limit $1',
        [limit],
      );
      return ok({
        items: rows.map((row) => ({
          id: Number(row.id),
          body: row.body,
          createdAt: row.created_at.toISOString(),
        })),
        count: rows.length,
      });
    } catch (cause) {
      return databaseFailure(cause);
    }
  },
});
