# Extreme Crafting Table

A NeoForge 1.21.1 mod that adds an extended workbench: a 27-slot ingredient
inventory with automated recipe matching, oversized stacks (up to 81920 per
slot), internal energy (FE), and JEI integration.

Port of Workbench Plus from QuarryPlus.

## Building

Requires Java 21 and Gradle 8.14:

```bash
gradlew build
```

The mod jar is produced at `build/libs/extremecraftingtable-<version>-neoforge-1.21.1.jar`.

## Features

- 27 ingredient slots driven by a recipe search + backtracking matcher
  (`WorkbenchRecipe.canMatch`), with live recipe output previews
- Automated item/energy access via NeoForge capabilities
  (`IItemHandler` / `IEnergyStorage`)
- Oversized slot counts persisted through a clamp+restore side tag
  (`SlotCounts`) that survives the 1.21.1 ItemStack codec ceiling
- JEI recipe category integration

## License

All rights reserved.
