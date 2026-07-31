# GTD Analytical Platform — Environment Setup

This file covers **installing and configuring everything the platform needs on your machine** — JDK, Maven, PostgreSQL, Git, the one-time database role, and environment variables — for **Windows**, **macOS**, and **Linux**.

Once your machine passes the verification checklist at the end, move on to `QUICKSTART.md` for building, running, logging in, and importing data. This file only gets you to a ready-to-build state; it doesn't cover running the app itself.

---

## 1. What you need

| Tool | Version | Why |
|---|---|---|
| JDK | 21 (LTS) | Frozen runtime (`CLAUDE.md` §2) |
| Maven | 3.9+ | Build tool |
| PostgreSQL | 16+ | Database |
| Git | any recent | Version control |
| Docker (optional) | any recent | Lets integration tests use a real Testcontainers-managed Postgres instead of the embedded fallback |

---

## 2. Install — macOS

```bash
brew install openjdk@21 maven postgresql@16 git
brew services start postgresql@16
```

Homebrew's JDK isn't symlinked onto your `PATH` by default. Point `JAVA_HOME` at it — add this line to `~/.zshrc` (or `~/.bash_profile` if you use bash) so it persists across terminal sessions:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Then reload it: `source ~/.zshrc`.

---

## 3. Install — Windows

1. **JDK 21** — install Eclipse Temurin 21 (or the Microsoft Build of OpenJDK 21). During/after install, set `JAVA_HOME` and add `%JAVA_HOME%\bin` to `PATH`:
   - GUI: System Properties → Environment Variables → New (`JAVA_HOME`) → edit `Path` to append `%JAVA_HOME%\bin`.
   - Or from an elevated PowerShell: `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.x.x"` — then open a **new** terminal for it to take effect.
2. **Maven** — download the binary zip from the Apache Maven site, extract it somewhere permanent (e.g. `C:\tools\apache-maven-3.9.9`), and add its `bin` folder to `PATH` the same way.
3. **PostgreSQL 16** — use the EnterpriseDB Windows installer. It also installs `psql` and pgAdmin, both of which you'll want.
4. **Git for Windows** — installing this also gives you **Git Bash**, a bash-compatible shell. If you'd rather not deal with PowerShell/`cmd` syntax at all, every macOS/Linux command in `QUICKSTART.md` works unmodified inside Git Bash.

---

## 4. Install — Linux (Debian/Ubuntu)

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven postgresql-16 git
sudo systemctl enable --now postgresql
```

RHEL/Fedora:
```bash
sudo dnf install -y java-21-openjdk maven postgresql16-server git
sudo postgresql-16-setup --initdb
sudo systemctl enable --now postgresql-16
```

---

## 5. Verify the install (any OS)

```bash
java -version     # should print 21.x
mvn -version      # should print 3.9+ and show the JDK 21 above as its runtime
psql --version    # should print 16.x
git --version
```

If `java -version` shows a different major version, `JAVA_HOME`/`PATH` is pointing at the wrong JDK — fix that before continuing; Maven builds silently pick up whatever `JAVA_HOME` resolves to.

---

## 6. One-time database setup (identical SQL on every OS)

```bash
psql -U postgres
```
```sql
CREATE ROLE gtd_app WITH LOGIN PASSWORD 'change-me';
CREATE DATABASE gtd_platform OWNER gtd_app;
\q
```

Use a real, unique password here if this machine isn't a throwaway local dev box — you'll reference it again in the next step and in pgAdmin.

---

## 7. Environment variables

The app reads its database credentials from the environment — never hardcoded in source or config files.

| Shell | Set for current session | Set persistently |
|---|---|---|
| macOS / Linux (bash/zsh) | `export GTD_DB_USERNAME=gtd_app`<br>`export GTD_DB_PASSWORD=change-me`<br>`export SPRING_PROFILES_ACTIVE=local` | add the same lines to `~/.zshrc` / `~/.bashrc` |
| Windows PowerShell | `$Env:GTD_DB_USERNAME="gtd_app"`<br>`$Env:GTD_DB_PASSWORD="change-me"`<br>`$Env:SPRING_PROFILES_ACTIVE="local"` | `setx GTD_DB_USERNAME "gtd_app"` (repeat per variable), then open a **new** terminal |
| Windows Git Bash | same `export` syntax as macOS/Linux above | same `~/.bashrc` approach |

---

## 8. Get the code

Copy or clone the `GTD-Platform` folder onto this machine, including the hidden `.git` directory — it's a self-contained git repository, no server/remote required.

**Get separately (not part of the repo):** the real GTD dataset (`globalterrorismdb_0718dist.csv`, ~155MB) if you plan to import real data. It's deliberately gitignored (large, licensed data) — place it at `GTD-Platform/data/globalterrorismdb_0718dist.csv` once you have it.

---

## 9. Verification checklist

Before moving on to `QUICKSTART.md`, confirm:

- [ ] `java -version` prints 21.x
- [ ] `mvn -version` prints 3.9+ and shows the JDK 21 above
- [ ] `psql --version` prints 16.x
- [ ] PostgreSQL service is running (`pg_isready -h localhost -p 5432` returns `accepting connections`)
- [ ] `gtd_app` role and `gtd_platform` database exist (step 6)
- [ ] `GTD_DB_USERNAME` / `GTD_DB_PASSWORD` / `SPRING_PROFILES_ACTIVE` are set in the terminal you'll build/run from
- [ ] The `GTD-Platform` folder (with `.git`) is present on this machine

All checked → continue to `QUICKSTART.md` for build, run, first login, and dataset import.

---

## 10. Related docs

- `QUICKSTART.md` — build, run, first login, importing data, pgAdmin, troubleshooting.
- `CLAUDE.md` — full frozen architecture and database schema.
- `docs/WINDOWS-SETUP.md` — additional detail specific to migrating this project between machines.
