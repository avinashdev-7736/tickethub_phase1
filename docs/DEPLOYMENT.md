# Deployment notes (S11-S13)

Everything below needs YOUR accounts/credentials; none of it could be provisioned from the build environment.

## 1. Images and CI
- One `Dockerfile`, build per module: `docker build --build-arg MODULE_PATH=services/booking-service -t tickethub/booking-service:1.0.0 .`
- `.github/workflows/ci.yml`: `mvn verify` on every push/PR, then builds each image (no push). Add a push step to ECR/Docker Hub when ready.
- Release: tag `v1.0.0` on main; images tagged with the same version. **Rollback** = redeploy the previous tag (schema changes are additive in Phase 1).

## 2. RDS (MySQL)
1. Create a MySQL 8 RDS instance (private subnet, security group allowing only the services).
2. Create databases `event_db`, `booking_db`, `payment_db`, `notification_db` and one user per database with rights on that database only.
3. Per service set: `DB_URL=jdbc:mysql://<rds-endpoint>:3306/<db>?serverTimezone=UTC`, `DB_USERNAME`, `DB_PASSWORD`.

## 3. Secrets (Secrets Manager or Parameter Store)
Keep out of git and out of the config server: `DB_PASSWORD`, `INTERNAL_API_KEY`, `PAYMENT_WEBHOOK_SECRET`,
`PAYMENT_RAZORPAY_KEY_ID/SECRET`, Keycloak admin credentials, SMTP credentials.
On ECS inject them as task-definition `secrets` (they arrive as environment variables - no code change needed).
On EC2/Compose, fetch with the AWS CLI at start-up into the container environment.
Evidence for the checklist: no secret value appears in `git log -p`, the images, or `config-repo`.

## 4. S3 posters (private bucket)
1. Create a bucket with **Block all public access ON**.
2. IAM policy for the event-service role/user: `s3:PutObject` and `s3:GetObject` on `arn:aws:s3:::<bucket>/posters/*` only.
3. Set `S3_BUCKET`, `S3_REGION`; credentials via the instance/task role (preferred) or `AWS_ACCESS_KEY_ID/SECRET`.
4. Test: `POST /events/{id}/poster` as ADMIN, then `GET /events/{id}` returns a `posterUrl` that expires after 15 minutes; the raw S3 URL must return 403.

## 5. Payment sandbox (Razorpay test mode)
1. Create test API keys; set `PAYMENT_PROVIDER=razorpay`, `PAYMENT_RAZORPAY_KEY_ID/SECRET`.
2. Expose payment-service webhook publicly (e.g. ngrok or the deployed gateway): `POST <public>/payments/webhook`.
3. In the dashboard create a webhook for `payment.captured` and `payment.failed` with a secret; set the same value as `PAYMENT_WEBHOOK_SECRET`.
4. Flow: `POST /payments` returns PENDING + `providerReference` (order id). Complete a test payment for that order in the dashboard checkout;
   the webhook then confirms the booking. Replay the same webhook: it must be ignored.
5. Verify the real request/response shapes against the current provider docs - this code has not been run against the sandbox.

## 6. Real email
Point `MAIL_HOST/PORT/USERNAME/PASSWORD` (+ `MAIL_SMTP_AUTH=true`, `MAIL_STARTTLS=true`) at your SMTP provider (e.g. SES SMTP). Verify the sender address with the provider.

## 7. Final end-to-end proof (ST-62 style)
Run `scripts/demo.sh` against the deployed gateway, then capture: the Jaeger trace of the booking+payment flow, the Kibana search by `bookingId`
(profile `elk`), the Grafana dashboard, one resilience scenario (README table) and the received email.
