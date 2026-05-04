# Permissions

Tessera currently registers a single permission node, declared in
`plugin.yml`.

## `tessera.command`

| Field       | Value                                        |
| ----------- | -------------------------------------------- |
| Node        | `tessera.command`                            |
| Default     | `op`                                         |
| Gates       | The entire `/tessera` command (all subcommands, including `debug`). |

By default, only operators can run `/tessera`. Players with this node can
run every subcommand, including the bake-time tuners that invalidate
the registry — there is currently no finer-grained split between user
and debug commands.

## Granting the node

The node is bound to the root `tessera` command, so any permission plugin
that hooks Bukkit's command system will work. Examples:

### LuckPerms

Grant the node to a group:

```
/lp group helper permission set tessera.command true
```

Or to an individual player:

```
/lp user <name> permission set tessera.command true
```

### Vanilla / built-in op

`tessera.command` defaults to `op`, so anyone op'ed via `/op <name>`
already has access — no extra configuration needed.

## Recommended scoping

The `debug` subcommands mutate static rotation / flip state and
invalidate the bake registry. They are intended for the server admin
running texture tuning, not for general staff. Because there is no
separate node for debug today, restrict `tessera.command` to operators
or a tightly-scoped admin group.

If you need finer separation, fence access to debug subcommands at your
permission plugin's command-level layer, e.g. LuckPerms regex matchers
on the typed command itself, until separate nodes land upstream.
