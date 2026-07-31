# GTD Analytical Platform — Quickstart

A fully offline Business Intelligence platform for the Global Terrorism Database: 15 analytical/administrative workspaces, session-based auth, PostgreSQL-backed. Every workspace is publicly viewable — signing in as admin is only needed to import a new dataset or change platform configuration.

This guide has exact commands for **macOS**, **Windows**, and **Linux**. Skip to your OS's install section, then follow the shared steps from "Get the code" onward — they're identical everywhere except environment-variable syntax.

For deeper background: `CLAUDE.md` (full architecture/spec), `docs/BUILD-PHASES.md` (how this was built, phase by phase), `docs/WINDOWS-SETUP.md` (cross-machine migration notes), `docs/PHASE-7-HARDENING-REPORT.md` (security/performance verification).

---

## 1. Install prerequisites

You need: **JDK 21**, **Maven 3.9+**, **PostgreSQL 16**, and **Git**. Docker is optional (only changes which integration-test strategy runs).

### macOS

```bash
brew install openjdk@21 maven postgresql@16 git
brew services start postgresql@16

# Point your shell at Homebrew's JDK (add this to ~/.zshrc or ~/.bash_profile to persist it):
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

### Windows

1. **JDK 21** — install Eclipse Temurin 21 (or Microsoft Build of OpenJDK 21). Set `JAVA_HOME` and add `%JAVA_HOME%\bin` to `PATH` (System Properties → Environment Variables, or `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.x.x"` then open a new terminal).
2. **Maven** — download the binary zip from Apache, extract it, add its `bin` folder to `PATH`.
3. **PostgreSQL 16** — the EnterpriseDB Windows installer (also installs `psql` and pgAdmin).
4. **Git for Windows** — also gives you Git Bash, a bash-compatible shell if you'd rather not deal with PowerShell/`cmd` syntax at all (every macOS/Linux command in this guide works unmodified in Git Bash).

### Linux (Debian/Ubuntu)

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven postgresql-16 git
sudo systemctl enable --now postgresql
```

(RHEL/Fedora: `sudo dnf install java-21-openjdk maven postgresql16-server git`, then `sudo postgresql-16-setup --initdb && sudo systemctl enable --now postgresql-16`.)

### Verify, any OS

```bash
java -version     # should print 21.x
mvn -version      # should print 3.9+ and show the JDK 21 above
psql --version     # should print 16.x
```

---

## 2. Get the code

Copy or clone the `GTD-Platform` folder onto the machine you're setting up. It's a git repository — a plain folder copy (including the hidden `.git` directory) carries full history with it; no server/remote required.

**Not included in that copy — get these separately:**
- The real GTD dataset (`globalterrorismdb_0718dist.csv`, ~155MB), if you're importing real data. Place it at `GTD-Platform/data/globalterrorismdb_0718dist.csv`. It's deliberately gitignored (large, licensed data).

---

## 3. One-time database setup (identical SQL on every OS)

```bash
psql -U postgres
```
```sql
CREATE ROLE gtd_app WITH LOGIN PASSWORD 'change-me';
CREATE DATABASE gtd_platform OWNER gtd_app;
\q
```

Use a real, unique password if this isn't a throwaway local dev machine.

---

## 4. Set environment variables

The app reads DB credentials from the environment — never hardcoded. Syntax differs by shell:

| Shell | Set for current session | Set persistently |
|---|---|---|
| macOS / Linux (bash/zsh) | `export GTD_DB_USERNAME=gtd_app`<br>`export GTD_DB_PASSWORD=change-me`<br>`export SPRING_PROFILES_ACTIVE=local` | add the same lines to `~/.zshrc` / `~/.bashrc` |
| Windows PowerShell | `$Env:GTD_DB_USERNAME="gtd_app"`<br>`$Env:GTD_DB_PASSWORD="change-me"`<br>`$Env:SPRING_PROFILES_ACTIVE="local"` | `setx GTD_DB_USERNAME "gtd_app"` etc., then open a **new** terminal |
| Windows Git Bash | same as macOS/Linux `export` syntax above | same `~/.bashrc` approach |

---

## 5. Build and run the full test suite

```bash
cd GTD-Platform
mvn clean verify
```

This does **not** need PostgreSQL running or the env vars above — the `test` profile provisions its own throwaway database automatically (a real Testcontainers-managed Postgres 16 if Docker is available, an embedded Postgres binary otherwise; never H2). If this passes, everything below will work.

---

## 6. Run the app

```bash
cd GTD-Platform
mvn spring-boot:run
```

(env vars from step 4 must be set in this same terminal session first). The app is now running at **http://localhost:8080**.

Alternative — package a jar and run it directly (closer to how it'd actually be deployed):
```bash
mvn clean package
java -jar target/gtd-platform.jar --spring.profiles.active=local
```

---

## 7. First login

Check the console output (or `logs/gtd-platform.log`) for a line printed once, on first startup only:
```
No admin_user found - bootstrapped a default account. username='admin' password='<random>'
```
That's your admin login — generated fresh per install, shown exactly once, no fixed default. Write it down immediately.

If you've lost it and need to set your own: see the "Resetting the admin password" section below.

---

## 8. Explore the platform

Open **http://localhost:8080** — no login required to browse. All 15 workspaces are open to anyone: Executive, Country, Region, Organization, Attack, Weapon, Target, Casualty, Statistical, Geospatial, Report, Administration (view-only), Data Quality Center, Audit & Activity Log, System Health Dashboard. Use the sidebar to navigate, or the search (Ctrl/Cmd+K) to jump anywhere.

Sign in (top-right "Admin sign-in" / home page "Use as Admin") only when you need to:
- Import a new dataset
- Change platform configuration (session timeout, import defaults, notifications) in the Administration Workspace

---

## 9. Importing the real GTD dataset

Once you're signed in as admin:

1. Go to the **Administration Workspace** from the sidebar.
2. Under "Dataset import," choose the CSV file (`data/globalterrorismdb_0718dist.csv` from step 2) and a mode:
   - **APPEND** — add new rows, leave existing ones untouched.
   - **REPLACE** — overwrite matching rows (matched by `eventid`), reassign them to the new dataset version.
3. Click **Upload & stage**. Progress (rows staged, rows rejected) shows live.
4. Once staging finishes, confirm the import from the same page to activate the new dataset version and refresh the materialized views.

Expect roughly 30–60 seconds for the full 181,691-row real file, comfortably inside the ≤5 minute target — see `docs/PHASE-7-HARDENING-REPORT.md` for the measured numbers.

---

## 10. Viewing the data directly (pgAdmin)

Register a server in pgAdmin with Host `localhost`, Port `5432`, Maintenance database `gtd_platform`, Username/Password matching step 3. Then Databases → `gtd_platform` → Schemas → `public` → Tables to browse `fact_incident`, `dim_country`, `import_batch`, `audit_log`, etc. directly. This only shows data while the app is running against the `local` profile — the `test` profile's database is torn down when tests finish.

---

## 11. Stopping the app

`Ctrl+C` in the terminal it's running in. PostgreSQL itself keeps running as a background service (started in step 1) — stop it separately if needed:
- macOS: `brew services stop postgresql@16`
- Linux: `sudo systemctl stop postgresql`
- Windows: Services app → PostgreSQL, or via `pg_ctl`/the installer's control panel

---

## 12. Troubleshooting

**Login fails with correct credentials / looks like a blank error page, not "invalid username or password":** you're likely running an older build before the CSRF token fix — pull latest, rebuild (`mvn clean package`), and fully restart the app (Java classes and cached Thymeleaf templates don't hot-reload).

**Resetting the admin password**, if lost:
```bash
pip install bcrypt --break-system-packages
python3 -c "import bcrypt; print(bcrypt.hashpw(b'YourChosenPassword', bcrypt.gensalt(rounds=10)).decode())"
```
```sql
UPDATE admin_user SET password_hash = '<hash from above>' WHERE username = 'admin';
```

**`git commit` fails with `Unable to create '.git/index.lock': File exists`:** a stray lock file from an interrupted git operation — safe to remove:
```bash
rm .git/index.lock
```

**Startup fails with a datasource connection error:** confirm PostgreSQL is actually running (`pg_isready -h localhost -p 5432`) and that `GTD_DB_USERNAME`/`GTD_DB_PASSWORD` are set in the same terminal session you're running the app from.

**`mvn` or `java` not found:** `PATH`/`JAVA_HOME` isn't set correctly for this terminal session — recheck step 1's verify commands.

---

## 13. Where to go from here

- `CLAUDE.md` — full frozen architecture, database schema, API conventions, non-functional targets.
- `docs/BUILD-PHASES.md` — the complete phase-by-phase build history and prompts, including the access-model decision (public viewing, admin-gated import/config).
- `docs/WINDOWS-SETUP.md` — more detail on moving this project between machines.
- `docs/PHASE-7-HARDENING-REPORT.md` — security review and measured performance against every NFR target.
- `README.md` — shorter developer-oriented reference covering the same setup, project layout, and logging config.
