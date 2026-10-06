# Bengali LKC — Production Deployment Guide

A step-by-step guide for putting Bengali LKC on a free **Azure for Students** server (no credit
card), with HTTPS, automatic deploys from GitHub release tags, daily backups and data that survives
every redeploy.

Follow the parts in order the first time. After that you only need [Part 9](#part-9--releasing-a-new-version-everyday-work).

---

## Part 0 — How it works

```text
             You publish a GitHub Release with tag  prod-1.2.0
                                   |
                                   v
   GitHub Actions (in the repo you tagged): checks -> build Docker image
                 -> push it to GitHub Container Registry (ghcr.io, private)
                                   |
                                   v
           SSH to the server (key can ONLY run the deploy script)
                                   |
                                   v
 deploy.sh: pull image -> back up DB -> switch ONE service -> health check
                                   |                  | fails
                                   v                  v
                                 Live         automatic rollback
```

Images are built on GitHub, not on the server: the free server has 1 GB of memory, enough to run
the site but not to build it.

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
├── backups/          database dumps
└── deploy-history.log
```

**What it costs:** the server (`B2ats_v2`, 750 free hours/month) and its 64 GB disk are free for
12 months, renewable each year while you are a student. The fixed public IP costs about
$3.65/month, paid from the $100 student credit. GitHub Actions and the container registry are
free.

---

## Part 1 — Prepare the code (on your PC)

1. Commit and push all work, then merge `dev-sazzad` into `main` in **both** repos.
2. Check both repos contain `.github/workflows/deploy-production.yml`, and the backend repo
   contains the `deploy/` folder and this file.

---

## Part 2 — Create the Azure server

### 2.1 Activate Azure for Students (no card)

1. Open <https://azure.microsoft.com/free/students> → **Start free**.
2. Sign in with a Microsoft account (or create one), enter your **university email** and click
   the link in the verification mail. Verify your phone by SMS. No card is asked.
3. You land in the **Azure Portal** (<https://portal.azure.com>). Search **Education** in the
   top bar to see your $100 credit.

### 2.2 Create the virtual machine

Portal → search **Virtual machines** → **Create** → **Azure virtual machine**.

**Basics tab**

| Field | Value |
|-------|-------|
| Subscription | Azure for Students |
| Resource group | *Create new* → `bengali-lkc-rg` |
| Virtual machine name | `bengali-lkc` |
| Region | **(Asia Pacific) Central India** (or South India / Southeast Asia) |
| Availability options | No infrastructure redundancy required |
| Security type | Trusted launch virtual machines (default) |
| Image | **Ubuntu Server 24.04 LTS - x64 Gen2** |
| VM architecture | **x64** |
| Size | *See all sizes* → search `B2ats_v2` → **Standard_B2ats_v2** (2 vCPU, 1 GiB) → Select |
| Authentication type | SSH public key |
| Username | `ubuntu` |
| SSH public key source | Generate new key pair |
| Key pair name | `bengali-lkc-key` |
| Public inbound ports | Allow selected ports |
| Select inbound ports | **SSH (22), HTTP (80), HTTPS (443)** |

The size must be exactly **Standard_B2ats_v2**; other sizes use up the credit. If it is not
offered in a region (or the region is refused for student accounts), choose another region.

**Disks tab**

- OS disk size: **64 GiB (P6)**
- OS disk type: **Premium SSD (locally-redundant storage)**
- Do not add data disks (they are not free).

**Networking tab**

- Virtual network / Subnet: keep the new defaults.
- Public IP: keep the new one (it is *Standard*, which is always **static**, so it never changes).
- NIC network security group: Basic; inbound ports 22, 80, 443 (from the Basics tab).
- Leave **Delete public IP and NIC when VM is deleted** unticked.

**Management tab**

- **Auto-shutdown: make sure it is OFF.** If it is on, the site goes offline every evening.

Keep the other tabs at their defaults → **Review + create** → **Create**.

A pop-up offers the key: click **Download private key and create resource**. You get
`bengali-lkc-key.pem`. **Azure never shows this key again**, so:

1. Create the folder `C:\Users\<you>\.ssh` if it does not exist.
2. Move the file there as `C:\Users\<you>\.ssh\bengali-lkc.pem`.
3. Keep a second copy somewhere safe (e.g. a USB drive).

Wait for **Your deployment is complete** → **Go to resource**. On the **Overview** page note the
**Public IP address** (the examples below use `20.193.10.20`).

Do **not** open 3000, 8080 or 5432 in the network security group.

### 2.3 Watch the credit

Portal → **Education** shows the remaining credit. Check it once a month; it should only go down
by about $4 (the public IP). If it drops faster, something other than the free VM and disk is
running.

---

## Part 3 — Free domain name (DuckDNS)

HTTPS is required (login cookies are `Secure`), and certificates need a domain name.

1. Go to <https://www.duckdns.org>, sign in with GitHub or Google.
2. Add a subdomain, e.g. `benlkc` → you get `benlkc.duckdns.org`.
3. Set **current ip** to your Azure public IP and press **update ip**.
4. Check from your PC: `nslookup benlkc.duckdns.org` shows that IP.

---

## Part 4 — Set up the server (once)

### 4.1 Connect

From Git Bash on your PC:

```bash
chmod 600 ~/.ssh/bengali-lkc.pem
ssh -i ~/.ssh/bengali-lkc.pem ubuntu@20.193.10.20
```

Answer `yes` to the fingerprint question. Everything below runs **on the server**.

### 4.2 Update, timezone and basic tools

```bash
sudo apt update && sudo apt -y full-upgrade
sudo timedatectl set-timezone Asia/Dhaka
sudo apt -y install git rsync curl ca-certificates unattended-upgrades
sudo reboot        # wait a minute, then ssh in again
```

### 4.3 Add swap (needed on a 1 GB server)

Swap lets the server use part of the disk as extra memory, so the four services fit in 1 GB.

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=20' | sudo tee /etc/sysctl.d/99-lkc-swap.conf
sudo sysctl --system
free -h            # "Swap:" should show 4.0Gi
```

Ports 80/443 are already open (Azure network security group, Part 2.2); Ubuntu's own firewall is
off on Azure, so nothing else is needed.

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

### 4.6 Read-only access to the backend repo (deploy key)

The server only needs the backend repo (for the `deploy/` folder). The website itself arrives as
Docker images.

```bash
ssh-keygen -t ed25519 -N "" -f ~/.ssh/github_backend -C "lkc-server-backend"
cat > ~/.ssh/config <<'EOF'
Host github-backend
  HostName github.com
  User git
  IdentityFile ~/.ssh/github_backend
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
ssh-keyscan github.com >> ~/.ssh/known_hosts
cat ~/.ssh/github_backend.pub     # copy this line
```

On GitHub: **Bengali_LKC_Backend** → Settings → **Deploy keys** → *Add deploy key* → title
`lkc-server`, paste the line, leave **Allow write access unticked** → *Add key*.

Clone it:

```bash
cd /opt/bengali-lkc
git clone git@github-backend:sazzadkhan20/Bengali_LKC_Backend.git backend
rsync -a backend/deploy/ deploy/
```

### 4.7 Let the server download your private images (GitHub token)

1. On GitHub (your account, not a repo): profile picture → **Settings** → **Developer settings**
   → **Personal access tokens** → **Tokens (classic)** → **Generate new token (classic)**.
2. Note: `lkc-server-pull`. Expiration: **No expiration** (or 1 year, and repeat this step when
   it expires). Tick **only `read:packages`**. → **Generate token** and copy it (starts with
   `ghp_`).
3. On the server, as `deploy`:

   ```bash
   docker login ghcr.io -u sazzadkhan20
   # Password: paste the token (nothing is shown while pasting), press Enter
   # -> "Login Succeeded"
   ```

This token can only download packages; it cannot change any code.

### 4.8 Create the secrets file

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
IMAGE_REGISTRY=ghcr.io/sazzadkhan20
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

### 4.9 Create the permanent volumes

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
ssh-keyscan 20.193.10.20
```

### 5.2 Add secrets in BOTH repos

GitHub → repo → Settings → **Environments** → *New environment* → `production`.
Inside it, under **Environment secrets**, add:

| Name                 | Value                                              |
|----------------------|----------------------------------------------------|
| `DEPLOY_HOST`        | `20.193.10.20` (your Azure public IP)              |
| `DEPLOY_USER`        | `deploy`                                           |
| `DEPLOY_SSH_KEY`     | whole content of `~/ci_deploy_key` (incl. BEGIN/END lines) |
| `DEPLOY_KNOWN_HOSTS` | the full output of `ssh-keyscan 20.193.10.20`      |

Under **Environment variables** add `PRODUCTION_URL` = `https://benlkc.duckdns.org`.

Optional: tick **Required reviewers** and add yourself, so every production deploy waits for
your click.

Do this for **Bengali_LKC_Backend** and **Bengali_LKC_Frontend**. Then delete the private key
from the server: `rm ~/ci_deploy_key`.

The workflows push images with GitHub's built-in token, so no extra secret is needed for that.

---

## Part 6 — First release

1. **Backend first.** GitHub → Bengali_LKC_Backend → **Releases → Draft a new release** →
   *Choose a tag* → type `prod-1.0.0` → *Create new tag on publish*, target `main` →
   title `prod-1.0.0` → **Publish release**.
2. Open the **Actions** tab and watch "Deploy backend to production" (jobs: verify → image →
   deploy). The first run takes around 15–20 minutes: the image is built on GitHub, and the
   lexicon is imported once on the server.
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

> If a workflow fails, open the failed step to read the log. Once the image job has succeeded,
> you can always run the same deployment by hand on the server:
> `bash /opt/bengali-lkc/deploy/scripts/deploy.sh backend prod-1.0.0`

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
mkdir -p ./lkc-backups
scp -i ~/.ssh/bengali-lkc.pem "ubuntu@20.193.10.20:/opt/bengali-lkc/backups/*.dump" ./lkc-backups/
```

**Test a restore** once, so you know it works:

```bash
bash /opt/bengali-lkc/deploy/scripts/restore-db.sh /opt/bengali-lkc/backups/<file>.dump
```

---

## Part 8 — Checking on the server

As `deploy` on the server (`ssh` in as `ubuntu`, then `sudo -iu deploy`):

```bash
bash /opt/bengali-lkc/deploy/scripts/status.sh     # live tags, containers, deploys, backups, memory, disk
cd /opt/bengali-lkc/deploy
docker compose -f compose.prod.yaml --env-file ../.env --env-file ../release.env logs -f backend
docker stats                                         # CPU / memory per container
```

The site's health endpoint is internal; `status.sh` shows each container as `healthy`.

---

## Part 9 — Releasing a new version (everyday work)

1. Work on `dev-sazzad`, test locally, merge into `main`, push.
2. Publish a GitHub Release with a **new** tag in the repo you changed:
   - backend change → Bengali_LKC_Backend release `prod-1.1.0`
   - frontend change → Bengali_LKC_Frontend release `prod-1.1.0`
   - both changed → backend first (wait for green), then frontend
3. Watch the Actions run. Only that service restarts (under a minute of downtime). All users,
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

  The last three images of each service stay on the server, so this takes seconds; older ones
  are downloaded again from ghcr.io.
- **Manual (data):** restore a backup with `restore-db.sh` (Part 7). Only do this for real data
  problems: it replaces everything written since that backup.

An older backend can run on a database migrated by a newer one, because Flyway ignores
migrations it does not know about. That is one more reason to keep migrations additive.

---

## Part 11 — Never do this on the server

- `docker compose down -v`, `docker volume rm bengali-lkc-pgdata`, `docker system prune -a --volumes`
- Change `POSTGRES_PASSWORD` in `.env` after the first start
- Edit or delete released files in `src/main/resources/db/migration/`
- Open ports 3000, 8080 or 5432 in the Azure network security group
- Resize the VM to another size (it starts using the credit) or turn on auto-shutdown
- Commit `.env` or any password to git

---

## Part 12 — Troubleshooting

| Symptom | What to check |
|---------|---------------|
| Actions `image` job: `denied` / `permission_denied` | Repo → Settings → Actions → General → Workflow permissions allows the workflow; the package (GitHub profile → Packages) is linked to this repo |
| Actions: `Permission denied (publickey)` | `DEPLOY_SSH_KEY` copied completely; the `authorized_keys` line exists for user `deploy` |
| Actions: `Host key verification failed` | `DEPLOY_KNOWN_HOSTS` is the output of `ssh-keyscan <IP>` |
| Deploy: `could not pull ghcr.io/...` | The `image` job succeeded; `docker login ghcr.io` was done **as `deploy`** (Part 4.7) and the token has not expired |
| Deploy: `tag prod-x not found` | Tag exists in the backend repo on GitHub; the server deploy key works (`ssh -T git@github-backend`) |
| Deploy: backend unhealthy | `status.sh`, then the backend logs (Part 8); usually a wrong value in `.env` |
| Site very slow, `free -h` shows swap almost full | Run `status.sh`; restart one service by deploying its live tag again; check that swap (Part 4.3) is active |
| `init-https.sh` fails | DuckDNS IP is correct; ports 80/443 allowed in the Azure network security group |
| Site loads but login fails | Use `https://` (not http); `DOMAIN` in `.env` matches the address you open |
| Emails not sent | `MAIL_USERNAME`/`MAIL_PASSWORD` (Gmail app password) in `.env`, then redeploy the backend tag |

After changing `.env`, apply it by deploying the current tag again:
`bash /opt/bengali-lkc/deploy/scripts/deploy.sh backend <live tag from status.sh>`.

---

## Later: your own domain or a bigger server

- **Own domain:** buy a domain, point it (Cloudflare DNS, *DNS only* / grey cloud) at the server
  IP, set `DOMAIN=` in `.env`, delete the old certificate
  (`docker run --rm -v bengali-lkc-letsencrypt:/etc/letsencrypt alpine rm -rf /etc/letsencrypt/live/lkc /etc/letsencrypt/archive/lkc /etc/letsencrypt/renewal/lkc.conf`),
  run `init-https.sh`, redeploy the backend tag (updates CORS) and update `PRODUCTION_URL` in GitHub.
- **More memory:** on a bigger server set larger values in `.env`, e.g.
  `BACKEND_JAVA_OPTS='-XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0'`,
  `FRONTEND_NODE_OPTIONS='--max-old-space-size=512'`, `DB_POOL_SIZE=20`, then redeploy both tags.
- **ARM server** (e.g. Oracle Ampere): add the repository variable `IMAGE_PLATFORMS` =
  `linux/arm64` in both repos so the images are built for ARM.
