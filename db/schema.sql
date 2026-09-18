-- 骨架附的示範資料表。
--
-- compose 把這個檔案掛進 PostgreSQL 的 /docker-entrypoint-initdb.d，所以資料庫第一次
-- 建立時會自動跑一次。volume 已經有資料時不會再跑——要重來就 `docker compose down -v`。
-- CI 則是用 psql 直接套用同一個檔案。

create table if not exists notes (
  id         bigint generated always as identity primary key,
  body       text not null check (length(btrim(body)) > 0),
  created_at timestamptz not null default now()
);

create index if not exists notes_created_at_idx on notes (created_at desc);
