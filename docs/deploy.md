# Deployment

This repository deploys to production with GitHub Actions.

## GitHub configuration

Add these repository variables:

- `DEPLOY_HOST=164.92.79.195`
- `DEPLOY_USER=root`
- `DEPLOY_PORT=22`

Add this repository secret:

- `DEPLOY_SSH_KEY`: contents of `~/.ssh/gh_actions`

## Server configuration

The workflow bootstraps a new isolated service called `botpingall` and leaves the existing `/opt/telegram-bot` service untouched.

After the first bootstrap, fill `/etc/botpingall/env` on the server:

```dotenv
BOT_TOKEN=your-telegram-token
BOT_DB_PATH=/opt/botpingall/shared/data/bot.db
```

The workflow refuses to replace the current release while `BOT_TOKEN` is empty.

## What the workflow does

- runs `./gradlew test distTar`
- uploads `BotPingAll-1.0-SNAPSHOT.tar`
- ensures the `botpingall` user, directories, env file, and `systemd` unit exist
- extracts each release into `/opt/botpingall/releases/<git-sha>`
- repoints `/opt/botpingall/current`
- restarts `botpingall.service`
- keeps the latest 3 releases and rolls back to the previous one if restart fails

## Manual rollback

If you need to roll back manually on the server:

```bash
ls -1 /opt/botpingall/releases
ln -sfn /opt/botpingall/releases/<previous-release> /opt/botpingall/current.next
mv -Tf /opt/botpingall/current.next /opt/botpingall/current
systemctl restart botpingall.service
```
