CREATE TABLE "mue_app"."activity_sessions" (
	"user_id" text NOT NULL,
	"id" text NOT NULL,
	"movement" text NOT NULL,
	"custom_movement_name" text,
	"environment" text NOT NULL,
	"started_on" date NOT NULL,
	"started_at_time" text,
	"duration_seconds" integer NOT NULL,
	"perceived_effort" integer,
	"notes" text,
	"source" text NOT NULL,
	"metrics" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"equipment" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"exercises" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL,
	CONSTRAINT "activity_sessions_user_id_id_pk" PRIMARY KEY("user_id","id")
);
--> statement-breakpoint
CREATE TABLE "mue_app"."agent_audit" (
	"id" text PRIMARY KEY NOT NULL,
	"agent_id" text NOT NULL,
	"tool_name" text NOT NULL,
	"occurred_at" timestamp with time zone DEFAULT now() NOT NULL,
	"mutation_id" text,
	"aggregates" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"result" text NOT NULL,
	"revision" bigint,
	"error" jsonb,
	CONSTRAINT "agent_audit_result_check" CHECK ("mue_app"."agent_audit"."result" in ('ok', 'error'))
);
--> statement-breakpoint
CREATE TABLE "mue_app"."custom_exercises" (
	"user_id" text NOT NULL,
	"id" text NOT NULL,
	"name" text NOT NULL,
	"name_folded" text NOT NULL,
	"tracking_mode" text NOT NULL,
	"equipment" text,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL,
	CONSTRAINT "custom_exercises_user_id_id_pk" PRIMARY KEY("user_id","id")
);
--> statement-breakpoint
CREATE TABLE "mue_app"."health_profile" (
	"user_id" text PRIMARY KEY NOT NULL,
	"height_cm" integer,
	"birth_date" date,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE "mue_app"."measurements" (
	"user_id" text NOT NULL,
	"date" date NOT NULL,
	"weight_cg" integer NOT NULL,
	"revision" bigint NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"last_mutation_id" text NOT NULL,
	"payload_schema_version" integer NOT NULL,
	CONSTRAINT "measurements_user_id_date_pk" PRIMARY KEY("user_id","date")
);
--> statement-breakpoint
CREATE TABLE "mue_app"."mutation_log" (
	"mutation_id" text PRIMARY KEY NOT NULL,
	"user_id" text NOT NULL,
	"aggregate_type" text NOT NULL,
	"aggregate_id" text NOT NULL,
	"operation" text NOT NULL,
	"status" text NOT NULL,
	"sequence" bigint,
	"revision" bigint,
	"result" jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "mutation_log_operation_check" CHECK ("mue_app"."mutation_log"."operation" in ('upsert', 'delete')),
	CONSTRAINT "mutation_log_status_check" CHECK ("mue_app"."mutation_log"."status" in ('applied', 'rejected'))
);
--> statement-breakpoint
CREATE TABLE "mue_app"."sync_counter" (
	"user_id" text PRIMARY KEY NOT NULL,
	"seq" bigint DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE TABLE "mue_app"."sync_journal" (
	"user_id" text NOT NULL,
	"sequence" bigint NOT NULL,
	"aggregate_type" text NOT NULL,
	"aggregate_id" text NOT NULL,
	"operation" text NOT NULL,
	"revision" bigint NOT NULL,
	"payload_schema_version" integer NOT NULL,
	"payload" jsonb,
	"deleted_at" timestamp with time zone,
	"origin_type" text NOT NULL,
	"origin_id" text,
	"mutation_id" text NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "sync_journal_user_id_sequence_pk" PRIMARY KEY("user_id","sequence"),
	CONSTRAINT "sync_journal_operation_check" CHECK ("mue_app"."sync_journal"."operation" in ('upsert', 'delete')),
	CONSTRAINT "sync_journal_sequence_check" CHECK ("mue_app"."sync_journal"."sequence" > 0)
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."account" (
	"id" text PRIMARY KEY NOT NULL,
	"issuer" text NOT NULL,
	"accountId" text NOT NULL,
	"providerId" text NOT NULL,
	"userId" text NOT NULL,
	"accessToken" text,
	"refreshToken" text,
	"idToken" text,
	"accessTokenExpiresAt" timestamp with time zone,
	"refreshTokenExpiresAt" timestamp with time zone,
	"scope" text,
	"password" text,
	"createdAt" timestamp with time zone DEFAULT now() NOT NULL,
	"updatedAt" timestamp with time zone NOT NULL
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."jwks" (
	"id" text PRIMARY KEY NOT NULL,
	"publicKey" text NOT NULL,
	"privateKey" text NOT NULL,
	"createdAt" timestamp with time zone DEFAULT now() NOT NULL,
	"expiresAt" timestamp with time zone,
	"alg" text,
	"crv" text
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."oauthAccessToken" (
	"id" text PRIMARY KEY NOT NULL,
	"token" text NOT NULL,
	"clientId" text NOT NULL,
	"sessionId" text,
	"userId" text,
	"referenceId" text,
	"authorizationCodeId" text,
	"resources" text[],
	"requestedUserInfoClaims" text[],
	"refreshId" text,
	"expiresAt" timestamp with time zone NOT NULL,
	"createdAt" timestamp with time zone NOT NULL,
	"revoked" timestamp with time zone,
	"confirmation" jsonb,
	"scopes" text[] NOT NULL,
	CONSTRAINT "oauthAccessToken_token_unique" UNIQUE("token")
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."oauthClient" (
	"id" text PRIMARY KEY NOT NULL,
	"clientId" text NOT NULL,
	"clientSecret" text,
	"clientDiscoveryId" text,
	"disabled" boolean DEFAULT false,
	"skipConsent" boolean,
	"enableEndSession" boolean,
	"subjectType" text,
	"scopes" text[],
	"clientCredentialsScopes" text[],
	"userId" text,
	"createdAt" timestamp with time zone,
	"updatedAt" timestamp with time zone,
	"name" text,
	"uri" text,
	"icon" text,
	"contacts" text[],
	"tos" text,
	"policy" text,
	"softwareId" text,
	"softwareVersion" text,
	"softwareStatement" text,
	"redirectUris" text[] NOT NULL,
	"postLogoutRedirectUris" text[],
	"backchannelLogoutUri" text,
	"backchannelLogoutSessionRequired" boolean,
	"tokenEndpointAuthMethod" text,
	"applicationType" text,
	"jwks" text,
	"jwksUri" text,
	"grantTypes" text[],
	"responseTypes" text[],
	"requirePKCE" boolean,
	"dpopBoundAccessTokens" boolean DEFAULT false,
	"referenceId" text,
	"metadata" jsonb,
	CONSTRAINT "oauthClient_clientId_unique" UNIQUE("clientId")
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."oauthClientAssertion" (
	"id" text PRIMARY KEY NOT NULL,
	"expiresAt" timestamp with time zone NOT NULL
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."oauthClientResource" (
	"id" text PRIMARY KEY NOT NULL,
	"clientId" text NOT NULL,
	"resourceId" text NOT NULL,
	"metadata" jsonb,
	"createdAt" timestamp with time zone
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."oauthConsent" (
	"id" text PRIMARY KEY NOT NULL,
	"clientId" text NOT NULL,
	"userId" text,
	"referenceId" text,
	"resources" text[],
	"requestedUserInfoClaims" text[],
	"scopes" text[] NOT NULL,
	"createdAt" timestamp with time zone NOT NULL,
	"updatedAt" timestamp with time zone NOT NULL
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."oauthRefreshToken" (
	"id" text PRIMARY KEY NOT NULL,
	"token" text NOT NULL,
	"clientId" text NOT NULL,
	"sessionId" text,
	"userId" text NOT NULL,
	"referenceId" text,
	"authorizationCodeId" text,
	"resources" text[],
	"requestedUserInfoClaims" text[],
	"expiresAt" timestamp with time zone NOT NULL,
	"createdAt" timestamp with time zone NOT NULL,
	"revoked" timestamp with time zone,
	"rotatedAt" timestamp with time zone,
	"rotationReplayResponse" text,
	"rotationReplayExpiresAt" timestamp with time zone,
	"authTime" timestamp with time zone,
	"confirmation" jsonb,
	"scopes" text[] NOT NULL,
	CONSTRAINT "oauthRefreshToken_token_unique" UNIQUE("token")
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."oauthResource" (
	"id" text PRIMARY KEY NOT NULL,
	"identifier" text NOT NULL,
	"name" text NOT NULL,
	"accessTokenTtl" integer,
	"refreshTokenTtl" integer,
	"signingAlgorithm" text,
	"signingKeyId" text,
	"allowedScopes" text[],
	"customClaims" jsonb,
	"dpopBoundAccessTokensRequired" boolean DEFAULT false,
	"disabled" boolean DEFAULT false,
	"createdAt" timestamp with time zone,
	"updatedAt" timestamp with time zone,
	"policyVersion" integer DEFAULT 1,
	"metadata" jsonb,
	CONSTRAINT "oauthResource_identifier_unique" UNIQUE("identifier")
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."session" (
	"id" text PRIMARY KEY NOT NULL,
	"expiresAt" timestamp with time zone NOT NULL,
	"token" text NOT NULL,
	"createdAt" timestamp with time zone DEFAULT now() NOT NULL,
	"updatedAt" timestamp with time zone NOT NULL,
	"ipAddress" text,
	"userAgent" text,
	"userId" text NOT NULL,
	CONSTRAINT "session_token_unique" UNIQUE("token")
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."user" (
	"id" text PRIMARY KEY NOT NULL,
	"name" text NOT NULL,
	"email" text NOT NULL,
	"emailVerified" boolean DEFAULT false NOT NULL,
	"image" text,
	"createdAt" timestamp with time zone DEFAULT now() NOT NULL,
	"updatedAt" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "user_email_unique" UNIQUE("email")
);
--> statement-breakpoint
CREATE TABLE "mue_auth"."verification" (
	"id" text PRIMARY KEY NOT NULL,
	"identifier" text NOT NULL,
	"value" text NOT NULL,
	"expiresAt" timestamp with time zone NOT NULL,
	"createdAt" timestamp with time zone DEFAULT now() NOT NULL,
	"updatedAt" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "mue_app"."activity_sessions" ADD CONSTRAINT "activity_sessions_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."custom_exercises" ADD CONSTRAINT "custom_exercises_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."health_profile" ADD CONSTRAINT "health_profile_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."measurements" ADD CONSTRAINT "measurements_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."mutation_log" ADD CONSTRAINT "mutation_log_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."sync_counter" ADD CONSTRAINT "sync_counter_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_app"."sync_journal" ADD CONSTRAINT "sync_journal_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."account" ADD CONSTRAINT "account_userId_user_id_fk" FOREIGN KEY ("userId") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthAccessToken" ADD CONSTRAINT "oauthAccessToken_clientId_oauthClient_clientId_fk" FOREIGN KEY ("clientId") REFERENCES "mue_auth"."oauthClient"("clientId") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthAccessToken" ADD CONSTRAINT "oauthAccessToken_sessionId_session_id_fk" FOREIGN KEY ("sessionId") REFERENCES "mue_auth"."session"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthAccessToken" ADD CONSTRAINT "oauthAccessToken_userId_user_id_fk" FOREIGN KEY ("userId") REFERENCES "mue_auth"."user"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthAccessToken" ADD CONSTRAINT "oauthAccessToken_refreshId_oauthRefreshToken_id_fk" FOREIGN KEY ("refreshId") REFERENCES "mue_auth"."oauthRefreshToken"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthClient" ADD CONSTRAINT "oauthClient_userId_user_id_fk" FOREIGN KEY ("userId") REFERENCES "mue_auth"."user"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthClientResource" ADD CONSTRAINT "oauthClientResource_clientId_oauthClient_clientId_fk" FOREIGN KEY ("clientId") REFERENCES "mue_auth"."oauthClient"("clientId") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthClientResource" ADD CONSTRAINT "oauthClientResource_resourceId_oauthResource_identifier_fk" FOREIGN KEY ("resourceId") REFERENCES "mue_auth"."oauthResource"("identifier") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthConsent" ADD CONSTRAINT "oauthConsent_clientId_oauthClient_clientId_fk" FOREIGN KEY ("clientId") REFERENCES "mue_auth"."oauthClient"("clientId") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthConsent" ADD CONSTRAINT "oauthConsent_userId_user_id_fk" FOREIGN KEY ("userId") REFERENCES "mue_auth"."user"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthRefreshToken" ADD CONSTRAINT "oauthRefreshToken_clientId_oauthClient_clientId_fk" FOREIGN KEY ("clientId") REFERENCES "mue_auth"."oauthClient"("clientId") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthRefreshToken" ADD CONSTRAINT "oauthRefreshToken_sessionId_session_id_fk" FOREIGN KEY ("sessionId") REFERENCES "mue_auth"."session"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."oauthRefreshToken" ADD CONSTRAINT "oauthRefreshToken_userId_user_id_fk" FOREIGN KEY ("userId") REFERENCES "mue_auth"."user"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mue_auth"."session" ADD CONSTRAINT "session_userId_user_id_fk" FOREIGN KEY ("userId") REFERENCES "mue_auth"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "activity_sessions_cursor_idx" ON "mue_app"."activity_sessions" USING btree ("user_id","started_on" DESC NULLS LAST,"id");--> statement-breakpoint
CREATE INDEX "activity_sessions_tombstone_idx" ON "mue_app"."activity_sessions" USING btree ("user_id","deleted_at") WHERE deleted_at is not null;--> statement-breakpoint
CREATE INDEX "agent_audit_agent_idx" ON "mue_app"."agent_audit" USING btree ("agent_id","occurred_at" DESC NULLS LAST);--> statement-breakpoint
CREATE UNIQUE INDEX "custom_exercises_name_folded_key" ON "mue_app"."custom_exercises" USING btree ("user_id","name_folded") WHERE deleted_at is null;--> statement-breakpoint
CREATE INDEX "custom_exercises_tombstone_idx" ON "mue_app"."custom_exercises" USING btree ("user_id","deleted_at") WHERE deleted_at is not null;--> statement-breakpoint
CREATE INDEX "measurements_tombstone_idx" ON "mue_app"."measurements" USING btree ("user_id","deleted_at") WHERE deleted_at is not null;--> statement-breakpoint
CREATE INDEX "mutation_log_retention_idx" ON "mue_app"."mutation_log" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "sync_journal_aggregate_idx" ON "mue_app"."sync_journal" USING btree ("user_id","aggregate_type","aggregate_id");--> statement-breakpoint
CREATE INDEX "sync_journal_recorded_at_idx" ON "mue_app"."sync_journal" USING btree ("recorded_at");