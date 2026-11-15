# 🍞 TeleportedBread

> *"Help me... I'm toast."*

A Paper plugin where **bread mutates every time you teleport**. Use `/warp`, `/home`, `/spawn` or a portal, and every single loaf in your inventory rolls its own chance to turn into something... less like bread.

Inspired by the teleported steak in *The Fly*. Seth Brundle would be proud. 🪰

[🇷🇺 По-русски ниже](#-по-русски)

---

## ✨ Features

- **Mutation tree.** Every bread type can branch into several others with its own chances. Bread can go radioactive, get genetically modified, or turn pink. Your call.
- **Per-item rolls.** A stack of 64 doesn't mutate as one, each loaf rolls separately. One step per teleport, max.
- **Eating effects.** Each mutation gives its own potion effects when eaten.
- **On-eat chaos** (all optional, per bread):
  - 🌀 **Particle aura** around the player for a while
  - 💥 **Explosion** with a chance, a fuse delay, optional block damage, fire and self-damage
  - 🧟 **Contagion**: hit someone to pass the effects on. It can be a transfer, can chain-spread between players, and can infect mobs
- **Custom looks.** Any vanilla item model, custom names, lore and enchantment glint, while it's still bread underneath.
- **Full in-game GUI editor.** `/tbread` opens a chest-based lab where you can create, edit and delete breads, set up branches, chances, effects, particles, explosions and contagion. No config editing needed, changes save and apply instantly.
- **Triggers:** teleport commands (configurable list, EssentialsX warmup-friendly) and nether/end portals and end gateways.

## 🌳 Default mutation tree

```
Bread
└ 50% Teleported Bread
   └ 7% Mutated Bread
      └ 3% Bread Substance
         └ 0.1% Bread?
            └ 0.05% Chunk of Radiation
               └ 0.05% Antimatter Goo   (50% chance to explode when eaten)
      └ 1% Radioactive Bread             (contagious, glows, may explode)
   └ 5% Altered Bread
      └ 3% GMO Bread                     (infects mobs with jump boost)
      └ 3% Pinkish Bread                 (spreads regeneration, hearts everywhere)
```

> The default config's item names and messages are in Russian. Rename everything right in the GUI.

## 📋 Requirements

- **Paper** or a Paper fork (Purpur, etc.), built for **Minecraft 1.21.8**
- Java 21
- ❌ **Doesn't work on plain Spigot/CraftBukkit**: it uses Paper-only APIs (Adventure, item models, the modern chat event)

## 🎮 Commands & permissions

| Command | What it does |
|---|---|
| `/tbread` or `/tbread gui` | Open the bread lab GUI |
| `/tbread give <id> [amount]` | Give yourself a bread (`plain` = regular bread) |
| `/tbread tree` | Print the mutation tree in chat |
| `/tbread reload` | Reload `config.yml` from disk |

Permission: `tbread.admin` (default: op)

### GUI controls cheat sheet

| Where | Controls |
|---|---|
| Bread list | LMB edit · Shift+LMB take 1 · Shift+RMB take a stack |
| Number buttons | LMB/RMB ±step · Shift ±other step · **Q** type exact value |
| Effects | LMB/RMB level ±1 · Shift time ±10s · **Q** rewrite · **Ctrl+Q** delete |
| Branches | LMB/RMB ±1% · Shift ±0.1% · **Q** exact chance · **Ctrl+Q** remove |
| Model | Click with any item on cursor to copy its look · RMB reset |
| Delete bread | Shift+RMB |

Text input goes through chat, and it's hidden from other players. Type `cancel` / `отмена` to go back without changes.

## ⚙️ Config example

```yaml
start:
  teleported: 50          # plain bread -> teleported, 50%

breads:
  radioactive:
    name: "&aRadioactive Bread"
    lore: [ "&7Clicks quietly near a Geiger counter" ]
    model: none            # or any item, e.g. "minecraft:slime_ball"
    glint: true
    effects:
      - "poison 1 15"      # effect level seconds
      - "glowing 1 60"
    next:                  # what it can mutate into next
      radiation_chunk: 0.5
    on-eat:
      particles: { type: dust, color: "#7CFC00", count: 6, spread: 0.5, duration-seconds: 30 }
      explosion: { chance: 2, power: 1.5, delay-seconds: 2, break-blocks: false, fire: false, self-damage: true }
      contagion: { duration-seconds: 90, chance: 25, effects: [ "poison 1 8" ], transfer: false, spreads: true, mobs: false }
```

The full default config with comments is generated on first launch.

## 🔨 Building

```bash
mvn package
```

The jar ends up in `target/`. Every push is built by GitHub Actions, and pushing a `v*` tag publishes a release with the jar attached.

---

## 🇷🇺 По-русски

Плагин для Paper, в котором **хлеб мутирует от телепортации**. Каждый `/warp`, `/home`, `/spawn` или портал, и каждый кусок хлеба в инвентаре отдельно роллит шанс превратиться во что-то всё менее похожее на хлеб.

- **Древо мутаций**: у каждого хлеба может быть несколько веток со своими шансами
- **Эффекты при поедании** + аура частиц, взрывы и зараза через удары
- **Полный GUI-редактор в сундуке**: `/tbread`, и всё настраивается кликами, без ковыряния в конфиге
- Работает на **Paper / Purpur 1.21.8**, на голом Spigot **не работает**

Команды: `/tbread` (GUI), `/tbread give <id> [кол-во]`, `/tbread tree`, `/tbread reload`. Право: `tbread.admin`.

---

## 📜 License

[GNU GPL v3.0](LICENSE). Fork it, mutate it, but keep it open. 🍞
