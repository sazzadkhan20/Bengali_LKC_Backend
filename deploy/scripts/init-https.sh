#!/usr/bin/env bash
# One-time: gets a free Let's Encrypt certificate for $DOMAIN and starts nginx.
# Port 80 must be open and DOMAIN must already point at this server.
# Renewal is automatic afterwards (certbot service + nginx reload).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DOMAIN="$(grep -E '^DOMAIN=' "$ENV_FILE" | cut -d= -f2- | tr -d "'\"")"
EMAIL="$(grep -E '^LETSENCRYPT_EMAIL=' "$ENV_FILE" | cut -d= -f2- | tr -d "'\"")"
[[ -n "$DOMAIN" && -n "$EMAIL" ]] || die "set DOMAIN and LETSENCRYPT_EMAIL in $ENV_FILE"

if cert_exists; then
  log "A certificate already exists; starting nginx"
else
  log "Requesting a certificate for $DOMAIN"
  dc stop nginx > /dev/null 2>&1 || true
  docker run --rm -p 80:80 -v bengali-lkc-letsencrypt:/etc/letsencrypt certbot/certbot:latest \
    certonly --standalone --non-interactive --agree-tos -m "$EMAIL" -d "$DOMAIN" --cert-name lkc
fi

dc up -d nginx certbot
dc exec -T nginx nginx -s reload > /dev/null 2>&1 || true
log "Live at https://$DOMAIN"
