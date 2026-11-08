# Deploying the CellGate bridge to Kubernetes

A complete walkthrough: build an image, get it onto the cluster, deploy, pair with the Home
app, and run it day to day. If you only want the short version, jump to
[The whole thing, in order](#the-whole-thing-in-order).

> **Read [Before you start](#before-you-start) first.** HomeKit needs mDNS multicast, which
> a normal pod cannot do. That one fact decides whether this can work on your cluster at
> all, and it is better to find out now than after three failed deploys.

---

## Before you start

### This needs a node on your home LAN

HomeKit discovery and resolution are mDNS: multicast to `224.0.0.251:5353` on the local
link. A pod on a CNI overlay can neither send nor receive it. The Home app would never find
the bridge, and a bridge that was already paired would stop resolving and show the gate and
doorbell as "No Response".

So the pod runs with `hostNetwork: true`, directly on a node's LAN interface. Three things
follow, and all three are hard requirements:

1. **A node must be on the same LAN/VLAN as your Apple TV or HomePod.** mDNS does not cross
   subnets. A cloud cluster cannot host this at all.
2. **That node's ports 8090 and 51829 must be free.**
3. **The namespace must allow `hostNetwork`.** The `baseline` and `restricted` Pod Security
   Standards both forbid it; `base.yaml` labels the namespace `enforce: privileged` for
   this reason.

Check you have a suitable node:

```bash
kubectl get nodes -o wide
```

Look for a node whose `INTERNAL-IP` is on your home subnet (`192.168.x.x`, `10.x.x.x`). If
none is, stop here — this cannot work on this cluster, and the honest options are a Pi or
mini PC joined to it, or running the bridge outside Kubernetes with
`cellgate-home.service`.

### What you need

- `kubectl` pointing at the cluster, and permission to create namespaces.
- A default `StorageClass` (`kubectl get storageclass`). On k3s that is `local-path`.
- Your CellGate email and password.
- Somewhere to publish a container image, below.

---

## Step 1 — get an image onto the cluster

### Option A: let CI build it (recommended)

`.github/workflows/image.yml` builds `linux/amd64` and `linux/arm64` and pushes to
`ghcr.io/chelin1974/cellgate-home` on every push to `main`, and on a `v*` tag.

```bash
git push origin main            # builds and pushes :latest
git tag v1.0.0 && git push origin v1.0.0   # builds and pushes :1.0.0
```

### Option B: build it yourself

Needs `docker buildx`. Build for the architecture your **node** runs, not your laptop — a
Raspberry Pi is `arm64`:

```bash
echo "$GITHUB_PAT" | docker login ghcr.io -u chelin1974 --password-stdin
docker buildx build --platform linux/arm64,linux/amd64 \
  -t ghcr.io/chelin1974/cellgate-home:latest --push .
```

The PAT needs the `write:packages` scope.

### Then make the image pullable

**GitHub Container Registry packages are private by default**, and a private package fails
with `ImagePullBackOff` and `denied`. Pick one:

- **Make the package public** — Profile → Packages → `cellgate-home` → Package settings →
  Change visibility → Public. Simplest, and the image contains nothing secret: the config
  arrives from a Secret at runtime.
- **Or create a pull secret:**

  ```bash
  kubectl -n cellgate-home create secret docker-registry ghcr-pull \
    --docker-server=ghcr.io \
    --docker-username=chelin1974 \
    --docker-password="$GITHUB_PAT"
  ```

  Then uncomment the `imagePullSecrets` block in `deployment.yaml`.

---

## Step 2 — label the node

The pod is pinned to the node on your LAN. Nothing schedules until this label exists:

```bash
kubectl label node <node-name> homekit=true
```

Use one node only. Two labelled nodes and a rescheduled pod means a bridge that moves
house, which breaks pairing.

## Step 3 — create the namespace and config

```bash
kubectl apply -f deploy/k8s/base.yaml
```

This bridge reads a **config file** rather than environment variables, so the Secret holds
the whole file and the pod mounts it at `/config/cellgate.config.json`. If you already have
a `cellgate.config.json` for running it locally, the helper script adapts it:

```bash
./deploy/k8s/create-secret.sh                             # reads ./cellgate.config.json
./deploy/k8s/create-secret.sh --dry-run                   # show what it would set
./deploy/k8s/create-secret.sh --bind-address 192.168.7.10
```

Two values have to differ inside the cluster, and the script rewrites them rather than
leaving you to remember:

- **`storagePath`** → `/data/cellgate-home.state.json`, the mounted volume. Left pointing at
  a path on your laptop, HomeKit pairing state would land on the container's ephemeral
  filesystem and be lost on every restart, re-pairing every controller.
- **`bindAddress`** → the node's LAN IP, if you pass `--bind-address`. The pod is pinned to
  one node, so a fixed value is safe and avoids a bad interface guess.

Starting from scratch instead:

```bash
cp cellgate.config.k8s.example.json cellgate.config.json
$EDITOR cellgate.config.json      # email, password, dashboardPasscode
./deploy/k8s/create-secret.sh
```

`cellgate.config.json` is in `.gitignore` — keep it that way; it holds your password.

## Step 4 — deploy

```bash
kubectl apply -k deploy/k8s
kubectl -n cellgate-home rollout status deploy/cellgate-home
```

If you do not run an ingress controller, drop `ingress.yaml` from `kustomization.yaml`
first — otherwise you create an Ingress nothing serves.

## Step 5 — pair with the Home app

The setup code is printed once at startup, and only while the bridge is unpaired:

```bash
kubectl -n cellgate-home logs deploy/cellgate-home | grep -A2 'setup code'
```

In the Home app: **Add Accessory → More options… → pick the bridge → enter the code**. The
gate and, if `doorbell` is on, the doorbell arrive together.

## Step 6 — check it

```bash
curl -s http://<node-ip>:8090/healthz      # {"status":"ok","discoverable":true}
open http://<node-ip>:8090/                # the Gate Board dashboard
```

`discoverable: true` is the one worth looking at: it means the mDNS advertisement is alive,
which is what HomeKit actually depends on.

---

## The whole thing, in order

```bash
kubectl get nodes -o wide                       # find a node on your home LAN
kubectl apply -f deploy/k8s/base.yaml
kubectl label node <node-name> homekit=true
./deploy/k8s/create-secret.sh                   # from your existing cellgate.config.json
kubectl apply -k deploy/k8s
kubectl -n cellgate-home rollout status deploy/cellgate-home
kubectl -n cellgate-home logs deploy/cellgate-home | grep -A2 'setup code'
```

---

## Running it day to day

### Reaching the dashboard

With `hostNetwork` it is already on the node: `http://<node-ip>:8090`. You do not need a
Service or an Ingress for that; they exist for a stable in-cluster name
(`http://cellgate-home.cellgate-home.svc:8090`) and for a hostname via `ingress.yaml`.

| | |
| --- | --- |
| `GET /` | the Gate Board dashboard |
| `GET /api/state` | gate state as JSON |
| `GET /healthz` | 200, or 503 once the mDNS responder has died |
| `GET /snapshot.jpg` | the latest camera still, **unauthenticated by design** so Homebridge or Scrypted can poll it |

`dashboardPasscode` guards opening the gate and taking pictures, but the page, the activity
log and the snapshot are readable by anyone who can reach the port. Keep it on your LAN and
off any public ingress.

### Upgrading

```bash
kubectl -n cellgate-home set image deploy/cellgate-home cellgate-home=ghcr.io/chelin1974/cellgate-home:1.1.0
kubectl -n cellgate-home rollout status deploy/cellgate-home
```

On `:latest` the tag does not change, so force a re-pull:

```bash
kubectl -n cellgate-home rollout restart deploy/cellgate-home
```

Expect a few seconds of downtime: the strategy is `Recreate`, not `RollingUpdate`, because
two pods would fight over the HomeKit port. Pairing survives — it lives on the PVC.

### Changing configuration

```bash
$EDITOR cellgate.config.json
./deploy/k8s/create-secret.sh                   # updates the Secret in place
kubectl -n cellgate-home rollout restart deploy/cellgate-home
```

The config is read once at startup, so the restart is required.

### Back up the pairing state

`cellgate-home-state` holds the bridge's HomeKit identity and every paired controller's
key. Lose it and you remove and re-add the bridge in the Home app, re-doing every room
assignment and automation. Back it up:

```bash
kubectl -n cellgate-home exec deploy/cellgate-home -- cat /data/cellgate-home.state.json > cellgate-home.state.json.bak
```

Keep that file somewhere private: it contains the setup code in plain text.

To restore, scale to zero, write the file back, and scale up:

```bash
kubectl -n cellgate-home scale deploy/cellgate-home --replicas=0
# restore /data/cellgate-home.state.json on the PVC, then:
kubectl -n cellgate-home scale deploy/cellgate-home --replicas=1
```

### Logs

```bash
kubectl -n cellgate-home logs -f deploy/cellgate-home
kubectl -n cellgate-home logs deploy/cellgate-home --previous   # after a restart
```

---

## Metrics and dashboards

This bridge has its own metrics stack, in this repository: `prometheus.yml`, and Grafana
provisioning plus a dashboard under `grafana/`. It shares nothing with the DaikinHome or
SpanHome bridges — separate config, separate storage, separate ports — so any one of them
runs with or without the others, and each Grafana shows exactly one bridge.

Under plain Docker:

```bash
docker compose --profile metrics up -d     # bridge + its own Prometheus + Grafana
```

Then set `"prometheusUrl": "http://localhost:9092"` in `cellgate.config.json` so the bridge
pushes to it, and open Grafana on <http://localhost:3002>.

The ports are allocated so all three bridges can also run side by side on one host:

| Bridge | Prometheus | Grafana |
| --- | --- | --- |
| SpanHome | 9090 | 3000 |
| DaikinHome | 9091 | 3001 |
| CellGateHome | 9092 | 3002 |

The bridge **pushes** OTLP metrics rather than being scraped, which is why Prometheus runs
with `--web.enable-otlp-receiver`. It emits one series, `cellgate_gate_open` — 1 while a
gate is open, 0 while closed, labelled by `gate` and `location` — and the dashboard is
built on that: current state, a state timeline, openings per hour, and share of time open.

In Kubernetes, point `prometheusUrl` at whatever Prometheus you already run; an in-cluster
one resolves by service name, because `dnsPolicy: ClusterFirstWithHostNet` keeps cluster DNS
working in a `hostNetwork` pod. There are no Prometheus or Grafana manifests in
`deploy/k8s` on purpose: most clusters already run one, and installing a second would be
worse than useless. The config files above are the source of truth either way — load them
into a ConfigMap if you do want a dedicated pair in this namespace.

---

## Why the manifests look like this

**`replicas: 1` and `strategy: Recreate`.** Two pods would both bind the HomeKit port, both
advertise the same bridge over mDNS, and both write the same pairing-state file. A
`RollingUpdate` starts the new pod before the old exits, so it would fail to bind the port
and sit in `CrashLoopBackOff` while the old pod carried on — the update would never
complete. Worse than a failed update: two bridges polling the same CellGate account, and a
doorbell that rings twice for one visitor.

**`dnsPolicy: ClusterFirstWithHostNet`.** Without it a `hostNetwork` pod inherits the node's
`resolv.conf` and loses cluster DNS, so it could not resolve an in-cluster Prometheus by
service name.

**`/healthz` is answered before the home-network check.** The dashboard 403s anything that
is not on a private network, and a liveness probe that got a 403 would fail forever and
restart the container in a loop — worse than disclosing a status word. Kubernetes probes
arrive from the node, whose address is usually RFC1918 and would pass the check anyway, but
a cluster on CGNAT space (`100.64/10`) would not.

---

## What happens when the CellGate cloud is down

Worth knowing before you go looking for a Kubernetes fault that isn't there. The bridge logs
in to CellGate, publishes the HomeKit accessories, and starts the dashboard **last**. So if
the CellGate cloud is unreachable when the pod starts, startup throws before the dashboard
exists, the process exits, and the pod goes into `CrashLoopBackOff` — backing off up to five
minutes between attempts. It recovers on its own once CellGate answers again.

Two consequences while that is happening: the dashboard is not available, so the only way to
see the cause is `kubectl logs`; and the cause currently arrives as a raw Java stack trace
rather than a one-line message, so read the first few lines rather than the last.

```bash
kubectl -n cellgate-home logs deploy/cellgate-home --previous | head -20
```

The probes cannot help with this — there is no listener to probe. The Daikin bridge
deliberately behaves the other way round (it comes up, serves, reports unhealthy and retries
in the background); making this one match would be a change to its startup sequence, not to
these manifests.

---

## When the mDNS responder dies

JmDNS can die under a running bridge — a network change on the node is enough — and when it
does it logs `Could not recover we are Down!` and never comes back. The bridge used to carry
on serving the dashboard and passing its liveness probe while being completely invisible to
Apple Home.

It no longer does. The bridge owns its JmDNS instance and registers for the library's
`cannotRecoverFromIOError` callback, and separately probes every 60 seconds that multicast to
`224.0.0.251` still works at all (three consecutive failures count as dead, so a DHCP renew
does not trigger anything). When either fires:

- `GET /healthz` turns **503** with the reason, which is what the liveness probe watches.
- The process logs the reason and exits 70, so the pod restarts immediately — a Kubernetes
  restart and `Restart=on-failure` under systemd both do the right thing.
- The dashboard itself keeps serving, so the reason stays readable right up to the restart.

Recovery is a restart, not an in-process repair: JmDNS does not come back, so rebuilding it
underneath a live HAP server would be a lot of machinery for a fault whose honest answer is
"start again".

```bash
kubectl -n cellgate-home logs deploy/cellgate-home --previous | grep -i "advertisement is dead"
```

---

## Running the other bridges on the same node

The sibling bridges are built the same way and are designed to share a node — their ports
do not collide:

| Bridge | HTTP | HomeKit | Namespace |
| --- | --- | --- | --- |
| DaikinHome | 8080 | 51826 | `daikin-home` |
| CellGateHome | 8090 | 51829 | `cellgate-home` |
| SpanHome | 8089 | 51828 | `span-home` |

Give them all the same `homekit=true` node label. Each is a separate bridge in the Home app
with its own setup code and its own pairing state.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `Pod ... violates PodSecurity "restricted"` | The namespace does not allow `hostNetwork`. Apply `base.yaml`, which labels it `enforce: privileged`. |
| Pod `Pending`, `node(s) didn't match selector` | No node has `homekit=true`. |
| `ImagePullBackOff` with `denied` | The GHCR package is private. Make it public or add the pull secret (Step 1). |
| `ImagePullBackOff` with `no match for platform` | The image was not built for the node's architecture. Rebuild with `--platform linux/arm64`. |
| Pod `Pending` on the volume | No default `StorageClass`, or a node-local volume bound to a different node. `kubectl -n cellgate-home describe pvc cellgate-home-state`. |
| `CrashLoopBackOff` right after deploying | Usually the CellGate cloud or the credentials; see the section above and read the first lines of the previous logs. |
| Bridge never appears in the Home app | mDNS is not reaching the phone. Confirm the node is on the same subnet, not a guest VLAN. |
| Accessories say "No Response", `/healthz` is 200 | A stale pairing state, or the node moved. Check the logs. |
| Accessories say "No Response", `/healthz` is 503 | The mDNS responder died. The pod should restart itself; see the section above. |
| Pairing lost on every restart | `storagePath` does not point at `/data`. Re-run `create-secret.sh`, which rewrites it. |
| Dashboard 403s from your laptop | Your laptop is not on a private network as the bridge sees it — check for a VPN. |
