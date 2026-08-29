CREATE TABLE "mue_app"."body_composition" (
	"user_id" text NOT NULL,
	"date" date NOT NULL,
	"formula_id" text NOT NULL,
	"formula_version" integer NOT NULL,
	"input_weight_cg" integer NOT NULL,
	"input_height_cm" integer NOT NULL,
	"input_age_years" integer NOT NULL,
	"input_sex" text NOT NULL,
	"body_fat_deci_percent" integer NOT NULL,
	"fat_free_mass_cg" integer NOT NULL,
	"body_water_deci_percent" integer NOT NULL,
	"resting_energy_kcal" integer NOT NULL,
	CONSTRAINT "body_composition_user_id_date_pk" PRIMARY KEY("user_id","date")
);
--> statement-breakpoint
ALTER TABLE "mue_app"."health_profile" ADD COLUMN "sex" text;--> statement-breakpoint
ALTER TABLE "mue_app"."measurements" ADD COLUMN "source_type" text DEFAULT 'manual' NOT NULL;--> statement-breakpoint
ALTER TABLE "mue_app"."measurements" ADD COLUMN "impedance_ohm" integer;--> statement-breakpoint
ALTER TABLE "mue_app"."body_composition" ADD CONSTRAINT "body_composition_measurement_fk" FOREIGN KEY ("user_id","date") REFERENCES "mue_app"."measurements"("user_id","date") ON DELETE cascade ON UPDATE cascade;