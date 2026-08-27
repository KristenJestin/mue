CREATE TABLE "mue_app"."food_log_entries" (
	"user_id" text NOT NULL,
	"id" text NOT NULL,
	"consumed_on" date NOT NULL,
	"consumed_at" text NOT NULL,
	"slot" text NOT NULL,
	"kind" text NOT NULL,
	"title" text NOT NULL,
	"estimation" text NOT NULL,
	"weighed_cooked" boolean NOT NULL,
	"energy_milli_kcal" integer,
	"protein_milligrams" integer,
	"carbs_milligrams" integer,
	"fat_milligrams" integer,
	"fibre_milligrams" integer,
	"source_ref" text,
	"amount_label" text,
	"quantity_thousandths" integer,
	"quantity_unit" text,
	"portions_thousandths" integer,
	"from_plan" text,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL,
	CONSTRAINT "food_log_entries_user_id_id_pk" PRIMARY KEY("user_id","id")
);
--> statement-breakpoint
CREATE TABLE "mue_app"."foods" (
	"user_id" text NOT NULL,
	"id" text NOT NULL,
	"name" text NOT NULL,
	"source" text NOT NULL,
	"reference_unit" text NOT NULL,
	"raw_label" text NOT NULL,
	"cooked_label" text NOT NULL,
	"energy_milli_kcal" integer,
	"protein_milligrams" integer,
	"carbs_milligrams" integer,
	"fat_milligrams" integer,
	"fibre_milligrams" integer,
	"brand" text,
	"barcode" text,
	"source_id" text,
	"source_version" text,
	"serving_label" text,
	"serving_thousandths" integer,
	"cooked_ratio_thousandths" integer,
	"image_ref" text,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL,
	CONSTRAINT "foods_user_id_id_pk" PRIMARY KEY("user_id","id")
);
--> statement-breakpoint
CREATE TABLE "mue_app"."meal_plan_entries" (
	"user_id" text NOT NULL,
	"planned_on" date NOT NULL,
	"slot" text NOT NULL,
	"recipe_id" text NOT NULL,
	"planned_servings_thousandths" integer NOT NULL,
	"consumed_log_entry_id" text,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL,
	CONSTRAINT "meal_plan_entries_user_id_planned_on_slot_pk" PRIMARY KEY("user_id","planned_on","slot")
);
--> statement-breakpoint
CREATE TABLE "mue_app"."recipes" (
	"user_id" text NOT NULL,
	"id" text NOT NULL,
	"name" text NOT NULL,
	"type" text NOT NULL,
	"base_servings" integer NOT NULL,
	"is_favourite" boolean NOT NULL,
	"description" text,
	"prep_time_minutes" integer,
	"image_ref" text,
	"ingredients" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"steps" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL,
	CONSTRAINT "recipes_user_id_id_pk" PRIMARY KEY("user_id","id")
);
--> statement-breakpoint
ALTER TABLE "mue_app"."food_log_entries" ADD CONSTRAINT "food_log_entries_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."foods" ADD CONSTRAINT "foods_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."meal_plan_entries" ADD CONSTRAINT "meal_plan_entries_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."recipes" ADD CONSTRAINT "recipes_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "food_log_entries_day_idx" ON "mue_app"."food_log_entries" USING btree ("user_id","consumed_on","consumed_at","id");--> statement-breakpoint
CREATE INDEX "food_log_entries_tombstone_idx" ON "mue_app"."food_log_entries" USING btree ("user_id","deleted_at") WHERE deleted_at is not null;--> statement-breakpoint
CREATE INDEX "foods_barcode_idx" ON "mue_app"."foods" USING btree ("user_id","barcode") WHERE barcode is not null and deleted_at is null;--> statement-breakpoint
CREATE INDEX "foods_tombstone_idx" ON "mue_app"."foods" USING btree ("user_id","deleted_at") WHERE deleted_at is not null;--> statement-breakpoint
CREATE INDEX "meal_plan_entries_tombstone_idx" ON "mue_app"."meal_plan_entries" USING btree ("user_id","deleted_at") WHERE deleted_at is not null;--> statement-breakpoint
CREATE INDEX "recipes_tombstone_idx" ON "mue_app"."recipes" USING btree ("user_id","deleted_at") WHERE deleted_at is not null;