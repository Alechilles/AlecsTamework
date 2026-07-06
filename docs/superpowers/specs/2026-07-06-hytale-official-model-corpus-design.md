# Hytale Official Model Corpus Design

## Purpose

Create a local derived corpus from official Hytale assets that helps Codex make better Hytale-compatible models, textures, attachments, animations, and style decisions.

The immediate motivation is the failed Flightmaster's Reins modeling loop: a technically valid `.blockymodel` is not enough. The model also needs the right Hytale proportions, silhouette, texture density, material treatment, construction logic, and rendered readability. This corpus is the foundation for making those qualities measurable and retrievable before future model work begins.

## Goals

- Build a source-backed corpus from official Hytale assets only.
- Cover all model families, not just items or props.
- Support both searchable reference lookup and future training/evaluation datasets.
- Preserve enough geometry, texture, semantic, and render metadata to improve actual model-making quality.
- Make concept-art-driven work systematic: concept analysis, official analog retrieval, construction planning, generation, render comparison, and revision.
- Keep the official asset release read-only and store only derived metadata, indexes, and generated evaluation artifacts in the mod/tooling workspace.

## Non-Goals

- Do not train a model in this design pass.
- Do not implement the extractor, renderer, or evaluator yet.
- Do not ingest non-official custom assets initially.
- Do not replace Blockbench or the Blockbench MCP. The corpus should make those tools more effective.
- Do not treat schema-valid output as sufficient quality. Visual comparison remains required.

## Research Baseline

Hytale Workshop MCP was queried against the official Hytale `0.5.6` asset release. The available source path was:

```text
C:\Users\22ale\.hytale-workshop-mcp\sources\hytale-shared-source-release
```

The release contains a broad asset set under `HytaleAssets`, including:

- `Common`: shared visual assets, textures, models, animations, UI, VFX, particles, and sounds.
- `Server`: model asset definitions and gameplay-side asset metadata.
- `Schema`: schema files.
- `Cosmetics`: cosmetic metadata.

Important counts observed in `HytaleAssets`:

- `.json`: about 32,835 files.
- `.png`: about 10,887 files.
- `.blockyanim`: about 6,736 files.
- `.ogg`: about 4,243 files.
- `.blockymodel`: about 2,825 files.
- `.particlespawner`: about 1,744 files.
- `.particlesystem`: about 598 files.

The `.blockymodel` files are concentrated in:

- `Common/Blocks`: about 1,151 models.
- `Common/NPC`: about 669 models.
- `Common/Items`: about 454 models.
- `Common/Cosmetics`: about 280 models.
- `Common/Characters`: about 168 models.
- `Common/Resources`: about 101 models.
- `Common/VFX`: 2 models.

The source appears to use `.blockymodel` as the model source format rather than `.bbmodel`, so the corpus must parse `.blockymodel` directly.

## Asset Taxonomy

The corpus should use both a physical path taxonomy and a semantic usage graph. Path taxonomy is useful for browsing official layout. Semantic usage is necessary because the same visual construction patterns appear across different directories.

### Block and World-Building Models

Primary source: `Common/Blocks`.

This family includes structures, roofs, fences, walls, stairs, decorative sets, foliage, farming, stone, tinkering, miscellaneous props, dungeon pieces, and Hypixel-specific blocks. It is the main source for placeable-object proportions, repeated set construction, block-adjacent geometry, furniture, village details, and environmental props.

### Item, Equipment, and Held-Object Models

Primary source: `Common/Items`.

This family includes weapons, tools, armor item forms, consumables, deployables, vehicles, projectiles, instruments, trinkets, back items, torches, capture crates, event props, and minigame items. It is the most important source for inventory-scale readability and held-item proportions.

### Resource and Inventory Material Models

Primary source: `Common/Resources`.

This family includes ingredients, materials, ores, plants, crystals, and first-aid resources. It is important for small object silhouettes, material swatches, simple readable geometry, and texture economy.

### Entity Body Models

Primary sources: `Common/NPC` and `Server/Models`.

This family covers beasts, critters, livestock, wildlife, undead, void, elementals, intelligent NPCs, humans, pets, bosses, flying entities, swimming entities, vehicles, deployables, and projectiles. These models need extra semantic metadata from `Server/Models` records, including model path, texture path, animation sets, hitbox, eye height, crouch offset, scale ranges, particles, trails, and phobia variants.

### Entity Attachments and Variant Parts

Primary sources: `Common/NPC/**/Attachments`, `Server/Models` default attachments, random attachment sets, and related model paths.

This family includes eyes, horns, crowns, crests, rattles, mouths, statue parts, and other creature-specific variant parts. It should be first-class because many believable designs are built from attachment logic: small readable pieces mounted to larger rigs.

### Player Character Customization

Primary sources: `Common/Characters` and `Common/Cosmetics`.

This family includes haircuts, beards, eyes, mouths, ears, face parts, arms, capes, gloves, headwear, pants, shoes, overtops, undertops, and other wearable pieces. It is the main reference source for player-scale attachments, cosmetic layering, body-relative pivots, and garment-like geometry.

### Animation and Pose Assets

Primary source: `.blockyanim` files under `Common/Characters`, `Common/NPC`, and related directories.

Animations are not model geometry, but they are required for a general Hytale modeler. They reveal bone naming, rig expectations, attachment motion, readable poses, and how models are meant to deform or move.

### Special-Case Visual Models

Primary sources: `Common/VFX`, `Common/Blocks/Icons`, projectile visuals, icon references, and spell or effect-adjacent models.

This family should stay separate because these assets may optimize for icon readability, VFX readability, or one-off presentation instead of normal in-world model behavior.

## Corpus Architecture

The corpus should be built as one ingestion pipeline with two primary outputs:

- A searchable style and reference database.
- A training/evaluation dataset for future generation and quality scoring tools.

The pipeline should not mutate official assets. It should read official files, normalize them into derived records, and attach generated artifacts such as rendered screenshots and comparison metrics.

### Raw Asset Registry

Tracks every official file with stable provenance:

- Hytale version.
- Source path.
- Relative asset path.
- File type.
- Hash.
- Top-level family.
- Sub-family.
- Official asset name inferred from path.
- Parse status and parse diagnostics.

This lets later tooling answer exactly which official release and which files influenced a generated model.

### Structured Model Index

Parses `.blockymodel` files into searchable geometry facts:

- Texture dimensions and texture references.
- Node count, cube count, plane count, and empty node count.
- Full node hierarchy and hierarchy depth.
- Local and global transforms.
- Bounds, pivots, offsets, rotations, and mirrored parts.
- Shape type, size, stretch, face visibility, double-sided faces, shading mode, and UV unwrap mode.
- Per-face UV rectangles, rotations, mirror flags, and pixel coverage.

The parser must handle official `.blockymodel` features observed in real files, including nested nodes, `box` and `none` node types, quaternion orientation, custom UV layouts, face rotation, face mirroring, hidden faces, double-sided faces, flat shading, and negative stretch used for mirroring.

### Texture Style Index

Reads `.png` texture metadata and image statistics:

- Width, height, transparency, and used pixel bounds.
- Dominant palette clusters.
- Outline colors and edge-darkening behavior.
- Highlight, midtone, and shadow ramps.
- Material-like regions such as leather, brass, wood, cloth, crystal, bone, stone, foliage, and metal when inferable.
- Texel density relative to model face coverage.
- Repeated motifs, stitches, rivets, gem facets, trim bands, and painted bevel cues.

The index should prioritize features that help author new textures, not just describe existing files.

### Semantic Usage Graph

Connects visual files to gameplay and usage metadata:

- `Server/Models` `ModelAsset` records.
- Model path.
- Texture path.
- Icon references.
- Hitboxes, eye height, crouch offset, sitting offset, sleeping offset, and scale ranges.
- Default attachments and random attachment sets.
- Animation sets.
- Particles and trails.
- Path-inferred item, block, cosmetic, resource, NPC, projectile, vehicle, VFX, or attachment usage.

This graph lets the system retrieve official examples by what an asset does, not only where it lives.

### Reference Sets

The corpus should generate curated reference sets for common modeling tasks. Examples:

- Small handheld weapons.
- Thick leather straps.
- Brass buckles and trim.
- Wing-like ornaments.
- Dangling gems.
- Harness-like attachments.
- Player-scale cosmetics.
- Flying beast parts.
- Creature horns and crests.
- Inventory-readable resources.
- Foliage planes.
- Block-adjacent furniture.

Reference sets can be seeded by taxonomy and then improved through tags, geometry features, texture features, and manual review.

### Render Samples

For each model where rendering is possible, store standard renders:

- Front.
- Back.
- Left.
- Right.
- Top.
- Bottom.
- Isometric.

Each render should record camera settings, image size, model scale, background, lighting, and source asset hash. These renders are essential because a model can be schema-valid while still looking wrong.

## Derived Data Schema

The first schema should favor model-making usefulness over archival completeness. Technical data still matters, but the most important fields are the ones that improve reference retrieval, construction planning, texture quality, and visual evaluation.

### `asset`

One record per source file.

- `asset_id`
- `version`
- `path`
- `type`
- `hash`
- `top_family`
- `sub_family`
- `official_name`
- `source_status`
- `parse_status`
- `parse_diagnostics`

### `model_geometry`

One record per parsed model.

- `asset_id`
- `texture_width`
- `texture_height`
- `node_count`
- `cube_count`
- `plane_count`
- `empty_node_count`
- `bounds`
- `origin`
- `pivot_summary`
- `rotation_summary`
- `hierarchy_depth`
- `uses_mirroring`
- `uses_stretch`
- `uses_custom_uv`
- `visual_density_score`
- `silhouette_complexity_score`

### `model_part`

One record per model node, cube, plane, or empty transform.

- `part_id`
- `asset_id`
- `name`
- `parent_part_id`
- `shape_type`
- `local_transform`
- `global_transform`
- `size`
- `offset`
- `pivot`
- `rotation`
- `visible_faces`
- `material_slot`
- `semantic_tags`
- `connectivity_status`

### `uv_face`

One record per textured face.

- `face_id`
- `part_id`
- `face_direction`
- `uv_rect`
- `uv_rotation`
- `uv_mirror_flags`
- `texture_pixel_coverage`
- `model_face_size`
- `stretch_ratio`
- `texel_density`
- `one_to_one_status`
- `issue_flags`

### `texture_profile`

One record per texture.

- `asset_id`
- `width`
- `height`
- `transparent_area_ratio`
- `used_bounds`
- `dominant_colors`
- `palette_clusters`
- `outline_colors`
- `highlight_ramps`
- `shadow_ramps`
- `material_region_candidates`
- `motif_candidates`
- `pixel_density_notes`

### `model_usage`

One record per known semantic usage edge.

- `usage_id`
- `asset_id`
- `usage_type`
- `server_model_asset_id`
- `model_path`
- `texture_path`
- `icon_path`
- `animation_set_ids`
- `attachment_edges`
- `hitbox`
- `scale_range`
- `path_context`

### `render_sample`

One record per standard render.

- `render_id`
- `asset_id`
- `view`
- `image_path`
- `camera`
- `lighting`
- `scale`
- `source_hash`
- `render_status`

### `quality_signal`

One record per derived or evaluated issue.

- `signal_id`
- `asset_id`
- `part_id`
- `face_id`
- `signal_type`
- `severity`
- `message`
- `suggested_fix`
- `source`

## Model-Making Workflow

The corpus should make future Hytale model production follow a fixed loop.

### 1. Input Analysis

Start with concept art, a text brief, or both. Extract visible components, materials, proportions, symmetry, intended use, must-preserve details, and likely model family. For concept art, produce annotated target views: front/readability, side depth, top footprint, and material regions.

### 2. Official Reference Retrieval

Query the corpus for official analogs by:

- Shape.
- Material.
- Function.
- Family.
- Construction pattern.
- Texture treatment.
- Scale.

For Flightmaster's Reins, the expected retrieval set would include leather straps, brass buckles, wing ornaments, dangling gems, harness-like attachments, and small inventory-scale objects.

### 3. Construction Plan

Before editing geometry, produce a blocky construction recipe:

- Target texture size.
- Model bounds.
- Cube and plane list.
- Approximate dimensions.
- Pivots.
- Layer order.
- Symmetry strategy.
- UV density target.
- Which details are modeled versus painted.
- Which official references justify the choices.

This plan should be concrete enough that a human could build the model from it in Blockbench.

### 4. Model and Texture Build

Generate or edit the `.blockymodel` and texture using strict rules:

- Do not stretch UVs unless the specific official reference pattern proves it is intentional and acceptable.
- Maintain consistent texel density.
- Keep connected parts connected unless a floating element is explicitly intended.
- Preserve readable silhouette in standard views.
- Use material-specific palette and shading rules from official examples.
- Prefer cube/plane construction patterns seen in official assets.

### 5. Render Set

Render front, back, left, right, top, bottom, and isometric views using fixed cameras. Save these next to the generated asset or in a derived evaluation folder. Future revisions should compare against the prior render set to catch regressions.

### 6. Evaluation

Score the model against both the concept and retrieved official references:

- Silhouette match.
- Proportion match.
- Material readability.
- Hytale style consistency.
- UV and texture quality.
- Connectivity and pivot sanity.
- In-game scale and inventory readability.

The evaluator should emit specific edits, not vague feedback. Examples:

- `Left strap is about 30% too thin compared with the concept and strap references.`
- `Gem dangle sits 2 cubes too low for inventory readability.`
- `Brass wing should use three stepped feather plates per side.`
- `UV face front_center.2 is stretched 2:1 and should be remapped to 1:1 texel density.`

### 7. Revision Loop

Apply the evaluator's concrete edits, rerender, and repeat until the model passes concept similarity, official-style similarity, and technical quality checks.

## Quality Checks

The first evaluator should include rule-based checks before any learned scoring exists.

Required checks:

- Stretched or inconsistent UV density.
- Hidden or missing important faces.
- Floating disconnected parts.
- Straps or limbs thinner than comparable official references.
- Oversized or undersized ornament pieces.
- Texture size mismatch.
- Unused texture area above an accepted threshold.
- Missing outline contrast for small inventory objects.
- Poor front-view readability.
- Excessive geometry fragmentation.
- Incoherent pivots or hierarchy.
- Texture colors that do not match the intended material family.

The checks should produce actionable messages and point to parts or faces where possible.

## Storage and Outputs

The implementation should keep the official release read-only and write derived corpus data under a local tooling location chosen during implementation planning.

Expected output formats:

- A queryable local database or set of structured indexes for reference retrieval.
- JSONL exports for future training and evaluation.
- Render images for standard views.
- Human-readable reports for selected reference sets and generated assets.

The exact storage backend should be chosen in the implementation plan. SQLite is a strong default because it supports structured queries, versioned derived data, and local portability without requiring a service.

## Success Criteria

The corpus is successful when it can materially improve a future model session. For a new concept-art-driven item, the system should be able to:

- Identify the closest official visual and semantic references.
- Explain which official construction patterns apply.
- Produce a concrete model construction plan before Blockbench editing begins.
- Flag stretched UVs and inconsistent texel density.
- Compare standard renders against the concept and references.
- Produce specific revision instructions.
- Preserve provenance back to the official Hytale version and source files.

## Implementation Notes for the Later Plan

The implementation should likely be decomposed into small packages or scripts:

- Official asset scanner.
- `.blockymodel` parser.
- Texture profiler.
- `Server/Models` semantic parser.
- Usage graph builder.
- Reference-set generator.
- Renderer integration.
- Quality checker.
- Query/report interface.

The implementation plan should start with a minimal vertical slice: scan official assets, parse a small set of `.blockymodel` files, connect them to textures, emit geometry and UV facts, and run a first UV/stretch quality report. Rendering and learned evaluation can follow once the base corpus is trustworthy.
