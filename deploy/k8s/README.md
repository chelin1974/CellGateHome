# Running the bridge in Kubernetes

```bash
cp cellgate.config.k8s.example.json cellgate.config.json
$EDITOR cellgate.config.json                # email, password, dashboardPasscode

kubectl apply -k deploy/k8s                 # namespace, PVC, deployment, service
kubectl -n cellgate-home create secret generic cellgate-home-config \
  --from-file=cellgate.config.json=./cellgate.config.json
kubectl label node <node-name> homekit=true # the node on your home LAN
kubectl -n cellgate-home logs -f deploy/cellgate-home   # setup code is printed here
```

The config file is mounted from that Secret at `/config/cellgate.config.json`. It is read
once at startup, so after editing the Secret:

```bash
kubectl -n cellgate-home rollout restart deploy/cellgate-home
```

## Read this before you deploy

**HomeKit does not work on a normal pod network.** Discovery and resolution are mDNS —
multicast to `224.0.0.251:5353` on the local link. A pod on a CNI overlay can neither send
nor receive that. The Home app would never find the bridge, and a bridge that was already
paired would stop resolving and show every accessory as "No Response".

That is why `deployment.yaml` sets `hostNetwork: true`. It is not a convenience; without it
(or a macvlan/ipvlan CNI) this cannot work. Three consequences follow:

1. **The pod must be scheduled on a node that is physically on the same LAN/VLAN as your
   Apple TV or HomePod.** mDNS does not cross subnets. Hence the `homekit=true`
   `nodeSelector` — set that label on the right node and nowhere else.
2. **The container's ports are the node's ports.** Nothing else on that node may bind
   `8090` or `51829`. (If you also run the Daikin bridge, it uses `8080` and `51826`, so
   the two can share a node.)
3. **A cloud cluster cannot host this at all.** If your nodes are in a datacentre there is
   no LAN to multicast onto. This needs a node at home.

**Back up the PVC.** `cellgate-home-state` holds the bridge's HomeKit identity and every
paired controller's key. Delete it and you re-pair from scratch in the Home app.

**Set `bindAddress` if the Home app cannot find the bridge.** `NoRouteToHostException: No
route to host` from JmDNS in the logs means the chosen interface cannot send multicast —
put the node's LAN IP in the config and restart. Because the pod is pinned to one node, a
fixed value is safe.

## Reaching the dashboard

Two ways, and you do not need the second:

- **Directly on the node.** Because of `hostNetwork`, `http://<node-ip>:8090/` already
  works. Simplest thing that can possibly work.
- **Through an Ingress.** `ingress.yaml` gives it a hostname (`gate.home.arpa` by
  default). Edit the host and `ingressClassName` to match your controller, or delete the
  file from `kustomization.yaml` if you do not run one.

In-cluster, the Service is at `http://cellgate-home.cellgate-home.svc:8090`.

`dashboardPasscode` guards opening the gate and taking pictures, but the page itself and
the activity log are readable by anyone who can reach the port. Keep it on your LAN.

`GET /snapshot.jpg` is unauthenticated by design so Homebridge or Scrypted can poll it —
worth remembering before you expose the port anywhere wider.

## Why one replica, and why Recreate

`replicas: 1` with `strategy: Recreate`. Two pods would both bind the HomeKit port, both
advertise the same bridge over mDNS, and both write the same pairing-state file. A
`RollingUpdate` starts the new pod before the old one exits, so it would fail to bind the
port and sit in `CrashLoopBackOff` while the old pod carried on — the update would never
complete. `Recreate` takes a few seconds of downtime instead.

Worse than a failed update: two bridges racing to poll the same CellGate account, and a
doorbell that rings twice for one visitor.

## What happens when the CellGate cloud is down

Worth knowing before you go looking for a Kubernetes fault that isn't there. The bridge
logs in to CellGate, publishes the HomeKit accessories, and starts the dashboard **last**.
So if the CellGate cloud is unreachable when the pod starts, startup throws before the
dashboard exists, the process exits, and the pod goes into `CrashLoopBackOff` — backing off
up to five minutes between attempts. It recovers on its own once CellGate answers again.

Two consequences while that is happening: the dashboard is not available, so the only way
to see the cause is `kubectl logs`; and the cause currently arrives as a raw Java stack
trace rather than a one-line message, so read the first few lines rather than the last.

```bash
kubectl -n cellgate-home logs deploy/cellgate-home --previous | head -20
```

The probes in `deployment.yaml` cannot help with this — there is no listener to probe. The
Daikin bridge deliberately behaves the other way round (it comes up, serves, reports
unhealthy and retries in the background); making this one match would be a change to its
startup sequence, not to these manifests.

## When the mDNS responder dies

JmDNS can die under a running bridge — a network change on the node is enough — and when
it does it logs `Could not recover we are Down!` and never comes back. The bridge used to
carry on serving the dashboard and passing its liveness probe while being completely
invisible to Apple Home.

It no longer does. The bridge owns its JmDNS instance and registers for the library's
`cannotRecoverFromIOError` callback, and separately probes every 60 seconds that multicast
to `224.0.0.251` still works at all (three consecutive failures count as dead, so a DHCP
renew does not trigger anything). When either fires:

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
