# Bengali LKC — Production Deployment Guide

A step-by-step guide for putting Bengali LKC on a free Oracle Cloud server, with HTTPS, automatic
deploys from GitHub release tags, daily backups and data that survives every redeploy.

Follow the parts in order the first time. After that you only need [Part 9](#part-9--releasing-a-new-version-everyday-work).

---

## Part 0 — How it works

```text
             You publish a GitHub Release with tag  prod-1.2.0
                                   |
                                   v
     GitHub Actions (in the repo you tagged): build + checks must pass
                                   |
                                   v
           SSH to the server (key can ONLY run the deploy script)
                                   |
                                   v
 deploy.sh: build image -> back up DB -> switch ONE service -> health check
                                   |                  | fails
                                   v                  v
                                 Live         automatic rollback
```

What runs on the one server (`deploy/compose.prod.yaml`):

| Service    | Purpose                                   | Reachable from internet |
|------------|-------------------------------------------|-------------------------|
| `nginx`    | HTTPS entry point (ports 80/443)          | Yes (only this)         |
| `frontend` | Next.js website + its `/api` routes       | No                      |
| `backend`  | Spring Boot API                           | No                      |
| `postgres` | Database (volume `bengali-lkc-pgdata`)    | No                      |
| `certbot`  | Renews the free HTTPS certificate         | No                      |

**What the database contains after the first deploy:** the lexicon imported from
`Documents/Bangla Mono Lingual Lexicons.xml`, plus one Platform Architect account
(`benlkc26`). No demo users and no demo data.

**Why redeploys never lose data:**

- The database lives in the Docker volume `bengali-lkc-pgdata`, declared `external`, so no
  compose command can delete it.
- Schema changes happen only through Flyway migrations (`V19__…sql`, `V20__…sql`, …), which add
  to the existing data.
- The XML import runs only when the lexicon is empty; on every later start it logs
  `skipping XML import`.
- The architect account is created only if `benlkc26` / `benlkc2026@gmail.com` does not exist,
  so a password changed in the app is never reset.
- Every backend deploy takes a database backup first.

Folder layout on the server:

```text
/opt/bengali-lkc/
├── .env              secrets (you create it; never in git)
├── release.env       which tag of each service is live (written by deploy.sh)
├── deploy/           compose file, nginx config, scripts (copied from backend repo)
├── backend/          git clone of Bengali_LKC_Backend (read-only deploy key)
├── frontend/         git clone of Bengali_LKC_Frontend (read-only deploy key)
├── backups/          database dumps
└── deploy-history.log
```

---

## Part 1 — Prepare the code (on your PC)

1. Commit and push all work, then merge `dev-sazzad` into `main` in **both** repos.
2. Check both repos contain `.github/workflows/deploy-production.yml`, and the backend repo
   contains the `deploy/` folder and this file.

---

## Part 2 — Create the Oracle Cloud server

1. Sign up at <https://www.oracle.com/cloud/free/>. Pick your **home region** carefully (e.g.
   Singapore or Mumbai, the closest to Bangladesh); Always Free servers can only be created there.
2. Console → **Compute → Instances → Create instance**:
   - **Name:** `bengali-lkc`
   - **Image:** Canonical **Ubuntu 24.04** (the aarch64/ARM build)
   - **Shape:** *Change shape → Ampere → VM.Standard.A1.Flex*, **2 OCPU, 12 GB memory**
     (shows "Always Free eligible").
   - **Networking:** create a new VCN with a **public subnet**, and tick **Assign a public IPv4 address**.
   - **SSH keys:** *Generate a key pair for me* → **download the private key** (keep it safe;
     e.g. save as `C:\Users\<you>\.ssh\oracle-lkc.key`).
   - **Boot volume:** 100 GB is still free (default 47 GB also works).
   - If you get "Out of capacity", try again later or pick another availability domain.
3. Make the IP permanent: Instance → **Attached VNICs → VNIC → IPv4 Addresses → Edit** →
   *Reserved public IP* → create one. Note the IP (example below: `152.67.10.20`).
4. Open the web ports: Instance → **Subnet → Default Security List → Add Ingress Rules**:
   - Source `0.0.0.0/0`, TCP, destination port **80**
   - Source `0.0.0.0/0`, TCP, destination port **443**

   Port 22 (SSH) is already open. Do **not** open 3000, 8080 or 5432.
5. Recommended: upgrade the account to *Pay As You Go*. Always Free resources stay free, and
   Oracle stops reclaiming "idle" free servers. Then set a **budget alert** (Billing → Budgets)
   of $1 so you get an email if anything ever costs money.

---

## Part 3 — Free domain name (DuckDNS)

HTTPS is required (login cookies are `Secure`), and certificates need a domain name.

1. Go to <https://www.duckdns.org>, sign in with GitHub or Google.
2. Add a subdomain, e.g. `benlkc` → you get `benlkc.duckdns.org`.
3. Set **current ip** to your reserved Oracle IP and press **update ip**.
4. Check from your PC: `nslookup benlkc.duckdns.org` shows that IP.

---

## Part 4 — Set up the server (once)

### 4.1 Connect

From Git Bash on your PC:

```bash
chmod 600 ~/.ssh/oracle-lkc.key
ssh -i ~/.ssh/oracle-lkc.key ubuntu@152.67.10.20
```

Everything below runs **on the server**.

### 4.2 Update, timezone and basic tools

```bash
sudo apt update && sudo apt -y full-upgrade
sudo timedatectl set-timezone Asia/Dhaka
sudo apt -y install git rsync curl ca-certificates unattended-upgrades
sudo reboot        # wait a minute, then ssh in again
```

### 4.3 Open ports 80/443 in the server firewall

Oracle's Ubuntu image blocks everything except SSH in its own firewall:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

### 4.4 Install Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
docker --version && docker compose version
```

Limit Docker log size (protects the disk):

```bash
echo '{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "5" } }' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
```

### 4.5 Create the `deploy` user and the app folder

All deployments run as this user; GitHub Actions logs in as this user too.

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
sudo mkdir -p /opt/bengali-lkc/backups
sudo chown -R deploy:deploy /opt/bengali-lkc
sudo -iu deploy          # you are now "deploy"; the rest of Part 4 runs as deploy
```

### 4.6 Read-only access to your private GitHub repos (deploy keys)

```bash
ssh-keygen -t ed25519 -N "" -f ~/.ssh/github_backend  -C "lkc-server-backend"
ssh-keygen -t ed25519 -N "" -f ~/.ssh/github_frontend -C "lkc-server-frontend"
cat > ~/.ssh/config <<'EOF'
Host github-backend
  HostName github.com
  User git
  IdentityFile ~/.ssh/github_backend
  IdentitiesOnly yes
Host github-frontend
  HostName github.com
  User git
  IdentityFile ~/.ssh/github_frontend
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
ssh-keyscan github.com >> ~/.ssh/known_hosts
cat ~/.ssh/github_backend.pub     # copy this line
cat ~/.ssh/github_frontend.pub    # and this one
```

On GitHub:

- **Bengali_LKC_Backend** → Settings → **Deploy keys** → *Add deploy key* → title `lkc-server`,
  paste `github_backend.pub`, leave **Allow write access unticked**.
- **Bengali_LKC_Frontend** → same, with `github_frontend.pub`.

Clone both repos:

```bash
cd /opt/bengali-lkc
git clone git@github-backend:sazzadkhan20/Bengali_LKC_Backend.git backend
git clone git@github-frontend:sazzadkhan20/Bengali_LKC_Frontend.git frontend
rsync -a backend/deploy/ deploy/
```

### 4.7 Create the secrets file

```bash
cp /opt/bengali-lkc/deploy/.env.example /opt/bengali-lkc/.env
chmod 600 /opt/bengali-lkc/.env
openssl rand -base64 32 | tr -d '/+='     # use as POSTGRES_PASSWORD
openssl rand -base64 48                   # use as JWT_SECRET
nano /opt/bengali-lkc/.env
```

Fill in every value. For you this means:

```env
DOMAIN=benlkc.duckdns.org
LETSENCRYPT_EMAIL=benlkc2026@gmail.com
POSTGRES_DB=bengali_lkc
POSTGRES_USER=lkc_admin
POSTGRES_PASSWORD=<first openssl output>
JWT_SECRET='<second openssl output>'
MAIL_USERNAME=benlkc2026@gmail.com
MAIL_PASSWORD=<16-letter Gmail app password, no spaces>
MAIL_FROM=benlkc2026@gmail.com
MAIL_FROM_NAME='Bengali LKC (No Reply)'
LKC_INITIAL_ARCHITECT_USERNAME=benlkc26
LKC_INITIAL_ARCHITECT_EMAIL=benlkc2026@gmail.com
LKC_INITIAL_ARCHITECT_NAME='Bengali LKC'
LKC_INITIAL_ARCHITECT_PASSWORD='<the architect password you chose>'
```

Save with `Ctrl+O`, `Enter`, `Ctrl+X`.

> **Keep `POSTGRES_PASSWORD` forever.** Postgres stores it the first time it starts; changing it
> in `.env` later breaks the connection.

The Gmail app password is created at <https://myaccount.google.com/apppasswords> (2-Step
Verification must be on).

### 4.8 Create the permanent volumes

```bash
docker volume create bengali-lkc-pgdata
docker volume create bengali-lkc-letsencrypt
```

---

## Part 5 — Connect GitHub Actions to the server

### 5.1 A key that can only deploy

Still as `deploy` on the server:

```bash
ssh-keygen -t ed25519 -N "" -f ~/ci_deploy_key -C "github-actions"
mkdir -p ~/.ssh && chmod 700 ~/.ssh
echo "command=\"bash /opt/bengali-lkc/deploy/scripts/ci-entry.sh\",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty $(cat ~/ci_deploy_key.pub)" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
cat ~/ci_deploy_key                      # private key -> GitHub secret (next step)
```

For the known-hosts value, run this **on your PC** (it records the server's identity):

```bash
ssh-keyscan 152.67.10.20
```

### 5.2 Add secrets in BOTH repos

GitHub → repo → Settings → **Environments** → *New environment* → `production`.
Inside it, under **Environment secrets**, add:

| Name                 | Value                                              |
|----------------------|----------------------------------------------------|
| `DEPLOY_HOST`        | `152.67.10.20` (your reserved IP)                  |
| `DEPLOY_USER`        | `deploy`                                           |
| `DEPLOY_SSH_KEY`     | whole content of `~/ci_deploy_key` (incl. BEGIN/END lines) |
| `DEPLOY_KNOWN_HOSTS` | the full output of `ssh-keyscan 152.67.10.20`      |

Under **Environment variables** add `PRODUCTION_URL` = `https://benlkc.duckdns.org`.

Optional: tick **Required reviewers** and add yourself, so every production deploy waits for
your click.

Do this for **Bengali_LKC_Backend** and **Bengali_LKC_Frontend**. Then delete the private key
from the server: `rm ~/ci_deploy_key`.

---

## Part 6 — First release

1. **Backend first.** GitHub → Bengali_LKC_Backend → **Releases → Draft a new release** →
   *Choose a tag* → type `prod-1.0.0` → *Create new tag on publish*, target `main` →
   title `prod-1.0.0` → **Publish release**.
2. Open the **Actions** tab and watch "Deploy backend to production". The first run takes
   around 10–15 minutes (Docker images build on the server, and the lexicon is imported once).
3. **Then the frontend:** same steps in Bengali_LKC_Frontend with tag `prod-1.0.0`.
4. When both are green, turn on HTTPS once (on the server, as `deploy`):

   ```bash
   bash /opt/bengali-lkc/deploy/scripts/init-https.sh
   ```

5. Open **https://benlkc.duckdns.org**, sign in with `benlkc26` and the architect password from `.env`.

Then:

- Change the password in the app (profile → change password).
- Optionally remove the `LKC_INITIAL_ARCHITECT_PASSWORD` line from `/opt/bengali-lkc/.env`; it
  is only read on the very first start.

> If a workflow fails, open the failed step to read the log. You can always run the same
> deployment by hand on the server: `bash /opt/bengali-lkc/deploy/scripts/deploy.sh backend prod-1.0.0`

---

## Part 7 — Backups

Every backend deploy already makes a backup. Add a daily one (as `deploy`):

```bash
crontab -e
```

Add this line, then save:

```cron
30 2 * * * bash /opt/bengali-lkc/deploy/scripts/backup-db.sh daily >> /opt/bengali-lkc/backups/backup.log 2>&1
```

Backups older than 14 days are deleted automatically. **Copy backups off the server**
regularly, e.g. weekly from your PC:

```bash
scp -i ~/.ssh/oracle-lkc.key "ubuntu@152.67.10.20:/opt/bengali-lkc/backups/*.dump" ./lkc-backups/
```

(Later you can upload them to Oracle Object Storage, 20 GB free.)

**Test a restore** once, so you know it works:

```bash
bash /opt/bengali-lkc/deploy/scripts/restore-db.sh /opt/bengali-lkc/backups/<file>.dump
```

---

## Part 8 — Checking on the server

As `deploy` on the server:

```bash
bash /opt/bengali-lkc/deploy/scripts/status.sh     # live tags, containers, deploys, backups, disk
cd /opt/bengali-lkc/deploy
docker compose -f compose.prod.yaml --env-file ../.env --env-file ../release.env logs -f backend
docker stats                                         # CPU / memory per container
df -h /                                              # disk
```

The site's health endpoint is internal; `status.sh` shows each container as `healthy`.

---

## Part 9 — Releasing a new version (everyday work)

1. Work on `dev-sazzad`, test locally, merge into `main`, push.
2. Publish a GitHub Release with a **new** tag in the repo you changed:
   - backend change → Bengali_LKC_Backend release `prod-1.1.0`
   - frontend change → Bengali_LKC_Frontend release `prod-1.1.0`
   - both changed → backend first (wait for green), then frontend
3. Watch the Actions run. Only that service restarts (a few seconds of downtime). All users,
   entries, submissions and other data stay exactly as they were.

Tag names must start with `prod-` and may not be reused. Use increasing versions:
`prod-1.0.1` (fix), `prod-1.1.0` (feature), `prod-2.0.0` (big change).

### Database changes — rules that keep data safe

- Add a **new** migration file for every schema change: `V19__short_description.sql`,
  `V20__…`. Never edit or delete a migration that has already been released.
- Prefer additive changes (new tables/columns, new nullable columns or with defaults). For a
  rename or removal, release in two steps: add the new thing and copy data, then remove the old
  thing in a later release.
- Never set `CLEAR_CORE_LEXICON_BEFORE_IMPORT=true` in production.

---

## Part 10 — Rollback

- **Automatic:** if a new version does not become healthy, `deploy.sh` switches back to the
  previous tag by itself and the Actions run is marked failed.
- **Manual (code):** deploy an older tag again, on the server:

  ```bash
  bash /opt/bengali-lkc/deploy/scripts/deploy.sh backend prod-1.0.0
  ```

  The last three images of each service are kept, so this takes seconds.
- **Manual (data):** restore a backup with `restore-db.sh` (Part 7). Only do this for real data
  problems: it replaces everything written since that backup.

An older backend can run on a database migrated by a newer one, because Flyway ignores
migrations it does not know about. That is one more reason to keep migrations additive.

---

## Part 11 — Never do this on the server

- `docker compose down -v`, `docker volume rm bengali-lkc-pgdata`, `docker system prune -a --volumes`
- Change `POSTGRES_PASSWORD` in `.env` after the first start
- Edit or delete released files in `src/main/resources/db/migration/`
- Open ports 3000, 8080 or 5432 in Oracle or the firewall
- Commit `.env` or any password to git

---

## Part 12 — Troubleshooting

| Symptom | What to check |
|---------|---------------|
| Actions: `Permission denied (publickey)` | `DEPLOY_SSH_KEY` copied completely; the `authorized_keys` line exists for user `deploy` |
| Actions: `Host key verification failed` | `DEPLOY_KNOWN_HOSTS` is the output of `ssh-keyscan <IP>` |
| Deploy: `tag prod-x not found` | Tag exists in that repo on GitHub; the server deploy key works (`ssh -T git@github-backend`) |
| Deploy: backend unhealthy | `status.sh`, then the backend logs (Part 8); usually a wrong value in `.env` |
| `init-https.sh` fails | DuckDNS IP is correct; ports 80/443 open in Oracle **and** iptables (Part 4.3) |
| Site loads but login fails | Use `https://` (not http); `DOMAIN` in `.env` matches the address you open |
| Emails not sent | `MAIL_USERNAME`/`MAIL_PASSWORD` (Gmail app password) in `.env`, then redeploy the backend tag |

After changing `.env`, apply it by deploying the current tag again:
`bash /opt/bengali-lkc/deploy/scripts/deploy.sh backend <live tag from status.sh>`.

---

## Later: your own domain

Buy a domain, point it (Cloudflare DNS, *DNS only* / grey cloud) at the server IP, set
`DOMAIN=` in `.env`, delete the old certificate
(`docker run --rm -v bengali-lkc-letsencrypt:/etc/letsencrypt alpine rm -rf /etc/letsencrypt/live/lkc /etc/letsencrypt/archive/lkc /etc/letsencrypt/renewal/lkc.conf`),
run `init-https.sh`, redeploy the backend tag (updates CORS) and update `PRODUCTION_URL` in GitHub.
