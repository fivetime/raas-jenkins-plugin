# RaaS Cloud plugin for Jenkins

Runs your Jenkins builds on ephemeral agents provided by a RaaS (Runner-as-a-Service) deployment on
OpenStack. One machine per build, created when a build queues, destroyed when it finishes.

You configure **two things**: the RaaS URL and an *application credential*. Labels, machine sizes, images
and networks are RaaS's knowledge; the plugin reads them from RaaS, so when your provider adds a size you
can use it a minute later without touching Jenkins.

## Requirements

- Jenkins **2.504 or newer** (LTS). Agents connect over WebSocket, which needs at least 2.217.
- Your Jenkins must be **reachable from the provider's cloud** over HTTPS: agents dial your controller's URL
  (Manage Jenkins → System → *Jenkins URL*). You do not open any inbound port; the agent side has none.
- An OpenStack **application credential** for the project RaaS serves you in. Create it in the provider's
  dashboard (Project → CI → Jenkins, or Identity → Application Credentials). Its *secret* is shown once.
- Your project has a **CI network** selected in the provider's dashboard (Project → CI). Agents get a second
  network card on it and reach your Jenkins from there. Without it RaaS refuses with `no_network`.

## Install

1. Download the latest `raas-jenkins.hpi` from the Releases page.
2. Manage Jenkins → Plugins → Advanced settings → Deploy Plugin → upload the file.
3. Manage Jenkins → Credentials → add a **Username with password** credential:
   username = application credential **id**, password = its **secret**.
4. Manage Jenkins → Clouds → New cloud → **RaaS**: enter the RaaS URL, pick the credential, click
   **Test connection**. It lists the labels you can use. If it does not, nothing else will work either —
   fix this first.

### Configuration as Code

If your controller is managed by JCasC, the cloud is one block; the credential can live in your usual
credentials source:

```yaml
jenkins:
  clouds:
    - raas:
        name: raas
        url: https://raas.tue.jp
        credentialsId: raas-app-credential
        connectTimeoutMinutes: 15
```

Note that JCasC owns the `clouds` list: a cloud added through the UI or a Groovy script is replaced on the
next JCasC reload, so put it in the YAML.

## Use

```groovy
pipeline {
  agent { label 'raas-ubuntu-24.04' }   // one of the labels Test connection listed
  stages {
    stage('build') { steps { sh 'docker version && make' } }
  }
}
```

Each build gets a fresh machine with Docker, the usual toolchains and outbound internet. When the build
ends the node is removed and RaaS destroys the machine. Builds never share a machine.

## What you will see when something is wrong

The plugin puts RaaS's reason in the Jenkins log instead of a timeout:

| In the log | Meaning | What to do |
|---|---|---|
| `no_network` | Your project has not selected a CI network | Provider dashboard → Project → CI → select network |
| `quota` | Your concurrent-agent limit is reached | Wait, or ask the provider to raise it |
| `pool_empty` | The size you asked for is being refilled | Nothing; Jenkins asks again automatically |
| `unknown_label` | RaaS does not offer that label (any more) | Click Test connection; use a listed label |
| `unreachable_jenkins` | The machine could not download `agent.jar` from your Jenkins | Your Jenkins URL must be reachable from the internet |
| `unauthorized` (Test connection) | The application credential is wrong or revoked | Create a new one; update the Jenkins credential |
| node stays offline, then disappears | Never connected within *Connect timeout* | Check the Jenkins URL is public and the controller is ≥ 2.217 |

## What RaaS holds

To connect the agent, RaaS receives the node's JNLP secret and hands it to the machine over an internal
mTLS channel. RaaS does not store it; it lives only on that machine and dies with it. The secret is valid
for that node name only and the node is deleted after the build.

## Build from source

```bash
JAVA_HOME=/path/to/jdk-21 mvn -B verify      # tests include a real (embedded) Jenkins
ls target/raas-jenkins.hpi
```
