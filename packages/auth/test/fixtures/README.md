# Test fixtures

`localhost-cert.pem` and `localhost-key.pem` are a self-signed certificate for
`localhost`, regenerated with:

```sh
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout localhost-key.pem -out localhost-cert.pem \
  -subj "//CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

They exist so `cimd-transport.test.ts` can point the real transport at a real
HTTPS server and watch it refuse a real redirect, instead of asserting against
a mock. The key is public, is trusted by nothing, and secures nothing: it is
passed to one test server on an ephemeral loopback port.
