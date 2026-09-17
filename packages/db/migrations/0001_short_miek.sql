CREATE TABLE "mcp_key" (
	"id" text PRIMARY KEY NOT NULL,
	"user_id" text NOT NULL,
	"label" text NOT NULL,
	"token_hash" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"last_used_at" timestamp with time zone,
	"revoked_at" timestamp with time zone
);
--> statement-breakpoint
ALTER TABLE "mcp_key" ADD CONSTRAINT "mcp_key_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "mcp_key_token_hash_key" ON "mcp_key" USING btree ("token_hash");--> statement-breakpoint
CREATE INDEX "mcp_key_user_idx" ON "mcp_key" USING btree ("user_id","revoked_at");