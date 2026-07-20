# Hytale Model Authoring System Design

## Purpose

Create an end-game Hytale model authoring system that helps Codex make high-quality Hytale-compatible models, textures, attachments, animations, and asset packages from concept art or text briefs.

The immediate motivation is the failed Flightmaster's Talisman modeling loop: a technically valid `.blockymodel` is not enough. The model also needs the right Hytale proportions, silhouette, texture density, material treatment, construction logic, rendered readability, and iterative critique. The final product should make those qualities systematic from the start.

The official asset corpus is the first subsystem, not the whole product. It supplies the source-backed examples, metadata, and quality signals that the authoring system uses for planning, generation, evaluation, and learning.

## Goals

- Design the full end-state product before implementing the first phase.
- Build a source-backed corpus from official Hytale assets only.
- Cover all model families, not just items or props.
- Support both searchable reference lookup and future training/evaluation datasets.
- Preserve enough geometry, texture, semantic, and render metadata to improve actual model-making quality.
- Make concept-art-driven work systematic: concept analysis, official analog retrieval, construction planning, generation, render comparison, and revision.
- Use Blockbench and the Blockbench MCP as an interactive authoring and inspection surface.
- Capture accepted attempts, failed attempts, feedback, and revisions so future sessions improve.
- Validate final assets for Hytale compatibility, mod layout, texture quality, and packaging readiness.
- Keep the official asset release read-only and store only derived metadata, indexes, and generated evaluation artifacts in the mod/tooling workspace.

## Non-Goals

- Do not train a model in this design pass.
- Do not implement the extractor, renderer, evaluator, generator, or packaging workflow yet.
- Do not ingest non-official custom assets initially.
- Do not replace Blockbench or the Blockbench MCP. The authoring system should make those tools more effective.
- Do not treat schema-valid output as sufficient quality. Visual comparison remains required.

## End-State Product

The final product is a local Hytale Model Authoring System. It should support the full workflow from idea to shippable mod asset:

1. Analyze concept art, sketches, screenshots, or text briefs.
2. Retrieve official Hytale references by shape, material, model family, usage, texture style, and rig context.
3. Produce a concrete construction plan before editing geometry.
4. Generate or revise `.blockymodel` geometry and `.png` textures under strict Hytale constraints.
5. Use Blockbench MCP for interactive inspection, structured edits, and model screenshots.
6. Render standard views and compare them against the concept and official references.
7. Produce actionable critique and apply revisions in a closed loop.
8. Save the attempt, renders, feedback, accepted fixes, and rejected patterns for future use.
9. Validate and package the finished asset into the mod with predictable naming and asset references.

This system should be useful for all Hytale asset families: items, props, blocks, resources, NPCs, player cosmetics, entity attachments, vehicles, projectiles, VFX models, and animation-aware rigs.

## System Architecture

The authoring system should be decomposed into small subsystems with explicit interfaces. The official corpus is the first dependency, but the complete product has several layers.

### 1. Official Asset Corpus

Reads official Hytale assets, parses `.blockymodel`, `.png`, `.blockyanim`, and `Server/Models` data, and exposes searchable official examples plus quality facts.

This subsystem answers:

- What official assets look like this?
- What construction patterns does Hytale use for this kind of thing?
- What texture size, palette, UV density, and silhouette complexity are typical?
- Which examples should guide a new model?

### 2. Concept Understanding

Turns concept art or a text brief into a structured target description:

- Object type and intended in-game usage.
- Visible parts and subassemblies.
- Materials and material regions.
- Front/back/side/top readability requirements.
- Symmetry, repeated motifs, ornaments, straps, handles, gems, wings, limbs, or attachments.
- Required details versus optional details.
- Approximate target scale and texture size.

For images, the system should produce annotated observations that can be checked before generation. It should not guess silently when the concept is ambiguous.

### 3. Reference Retriever

Queries the corpus for official analogs and groups them by why they matter:

- Shape analogs.
- Material analogs.
- Texture treatment analogs.
- Family and usage analogs.
- Rig, attachment, or animation analogs.
- Scale and readability analogs.

The retriever should produce a compact reference pack with paths, screenshots, relevant parts, texture notes, and construction takeaways.

### 4. Construction Planner

Produces a blocky implementation recipe before any model edits:

- Target model bounds and origin.
- Texture size and UV density target.
- Cube and plane inventory.
- Part hierarchy and pivots.
- Layering and connectivity.
- Symmetry and mirroring strategy.
- Which details are geometry versus painted texture.
- Material palette plan.
- Relevant official references for each major decision.

The plan should be specific enough that a human could build the asset in Blockbench from the document alone.

### 5. Geometry and Texture Generator

Creates or revises `.blockymodel` and `.png` files from the construction plan. It should enforce:

- Hytale-compatible `.blockymodel` syntax.
- Cube and plane geometry only when required by the target format.
- No accidental UV stretching.
- Consistent texel density.
- Connected geometry unless floating parts are intentional.
- Pivots and hierarchy appropriate to the model family.
- Texture palettes and shading patterns derived from official references.

The generator should support both first-pass creation and targeted patching from evaluator feedback.

### 6. Blockbench MCP Workspace

Uses Blockbench as the interactive editing and inspection layer:

- Load generated models and textures.
- Apply targeted geometry and UV edits.
- Inspect hierarchy, pivots, and face mappings.
- Produce standard screenshots.
- Let the user visually inspect and guide revisions.

The MCP workflow should be treated as a reliable authoring surface, not only a last-mile export tool.

### 7. Renderer and Comparator

Produces repeatable views for official references and generated models:

- Front.
- Back.
- Left.
- Right.
- Top.
- Bottom.
- Isometric.
- Optional in-hand, inventory, or entity-attached context renders when relevant.

The comparator should evaluate the generated model against both the concept and the official reference pack.

### 8. Evaluator and Critic

Turns render and metadata comparisons into concrete revision instructions. It should identify:

- Silhouette mismatch.
- Proportion mismatch.
- Texture density problems.
- Palette and material mismatches.
- Floating pieces.
- Bad pivots or hierarchy.
- Overly thin straps, limbs, handles, or ornaments.
- Under-modeled details that should be geometry.
- Over-modeled details that should be texture.
- Low inventory readability.

Feedback must point to specific parts, faces, views, or texture regions whenever possible.

### 9. Learning Store

Saves generation attempts and reviewer outcomes:

- Prompt or concept input.
- Reference pack.
- Construction plan.
- Generated model and texture versions.
- Renders.
- Evaluator findings.
- User feedback.
- Accepted fixes.
- Rejected attempts and failure reasons.

This is not necessarily model training at first. It is a structured memory that makes future authoring sessions less likely to repeat the same mistakes.

### 10. Benchmark Suite

Maintains challenge prompts and expected quality gates across all asset families:

- Inventory item.
- Held weapon.
- Resource material.
- Block prop.
- Decorative block set piece.
- NPC body.
- Creature attachment.
- Player cosmetic.
- Vehicle or deployable.
- Projectile or VFX model.
- Animation-aware rig or attachment.

Benchmarks should include official references, concept inputs where useful, expected constraints, and standard rendered comparisons.

### 11. Asset Packager

Validates and places finished assets into a mod-ready layout:

- `.blockymodel` and texture path conventions.
- Item, block, entity, attachment, or cosmetic asset references.
- Icon or inventory render where applicable.
- Schema validation.
- Texture size and power/multiple constraints.
- Regression tests or asset-load checks where available.

This subsystem should prevent a polished model from failing because of naming, path, schema, or packaging mistakes.

## End-to-End Flow

The final workflow should be:

```text
Concept or brief
  -> concept understanding
  -> official reference retrieval
  -> construction plan
  -> geometry and texture generation
  -> Blockbench MCP inspection
  -> standard renders
  -> concept/reference comparison
  -> evaluator feedback
  -> targeted revision loop
  -> benchmark and quality gates
  -> asset packaging
```

The critical discipline is that generation does not begin until the system has a reference pack and construction plan. This is the main process change intended to avoid technically valid but visually poor outputs.

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

## Official Corpus Subsystem

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

The complete authoring system should make future Hytale model production follow a fixed loop.

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

For Flightmaster's Talisman, the expected retrieval set would include leather straps, brass buckles, wing ornaments, dangling gems, harness-like attachments, and small inventory-scale objects.

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

The first evaluator should include rule-based checks before any learned scoring exists. Later evaluators can add image similarity, learned style scoring, and benchmark-specific scoring, but rule-based checks remain necessary because they catch concrete Hytale asset defects.

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

The implementation should keep the official release read-only and write derived corpus data under a local tooling location chosen during implementation planning. Generated models, renders, evaluator reports, benchmark results, and packaged assets should be stored separately from the official corpus so provenance stays clear.

Expected output formats:

- A queryable local database or set of structured indexes for reference retrieval.
- JSONL exports for future training and evaluation.
- Render images for standard views.
- Human-readable reports for selected reference sets and generated assets.
- Construction plans for generated assets.
- Iteration histories for attempts, feedback, and accepted revisions.
- Mod-ready asset packages or patch sets when the asset is finalized.

The exact storage backend should be chosen in the implementation plan. SQLite is a strong default because it supports structured queries, versioned derived data, and local portability without requiring a service.

## Success Criteria

The end-state system is successful when it can take concept art or a text brief through a repeatable path to a Hytale-compatible asset that passes visual, technical, and packaging checks.

For a new concept-art-driven item, the system should be able to:

- Analyze the concept into parts, materials, proportions, and required views.
- Identify the closest official visual and semantic references.
- Explain which official construction patterns apply.
- Produce a concrete model construction plan before Blockbench editing begins.
- Generate or revise `.blockymodel` and `.png` files from that plan.
- Use Blockbench MCP to inspect, edit, and render the result.
- Flag stretched UVs and inconsistent texel density.
- Compare standard renders against the concept and references.
- Produce specific revision instructions.
- Save accepted fixes and rejected attempts into the learning store.
- Package the finished asset into the mod layout with valid references.
- Preserve provenance back to the official Hytale version and source files.

## Delivery Phases

The implementation plan should preserve the end-state architecture but deliver it in vertical slices.

### Phase 1: Official Corpus Vertical Slice

Build the source-backed foundation:

- Official asset scanner.
- `.blockymodel` parser.
- Texture profiler.
- `Server/Models` semantic parser.
- Usage graph builder.
- Basic reference-set generator.
- First UV/stretch quality report.
- Query/report interface for official examples.

This phase should prove that official assets can be parsed into useful geometry, UV, texture, and semantic facts.

### Phase 2: Render and Visual Reference Pack

Add repeatable renders and reference packs:

- Standard renders for selected official examples.
- Reference-pack generation by query.
- Compact visual reports with paths, screenshots, and construction takeaways.
- Basic silhouette and proportion metrics.

This phase should make official references easy to inspect before model generation begins.

### Phase 3: Construction Planner

Add planning from concept or brief:

- Concept/brief analysis schema.
- Official reference selection for each major part.
- Cube/plane construction recipe.
- Texture size and palette plan.
- Human-readable plan output.

This phase should force the reference-first, plan-before-generation workflow.

### Phase 4: Generator and Blockbench Loop

Add model creation and interactive inspection:

- First-pass `.blockymodel` and texture generation from a construction plan.
- Blockbench MCP load/edit/render workflow.
- Targeted patching from evaluator instructions.
- Standard render export for generated assets.

This phase should make the system capable of producing and revising real assets.

### Phase 5: Evaluator, Learning Store, and Benchmarks

Add the critic and memory layer:

- Rule-based Hytale quality checks.
- Concept/reference render comparison.
- Specific revision suggestions.
- Attempt history and accepted/rejected feedback storage.
- Benchmark suite across model families.

This phase should make quality improvement measurable and repeatable.

### Phase 6: Packaging and Mod Integration

Add final asset readiness:

- Mod layout placement.
- Asset reference generation.
- Icon or inventory render handling.
- Schema validation.
- Asset-load or regression checks where available.
- Final package/report output.

This phase should turn a polished generated model into something that can be used in Tamework without manual wiring mistakes.

## Implementation Notes for the Later Plan

The first implementation plan should still start with Phase 1, because every later subsystem depends on trustworthy official parsing and metadata. The plan should avoid building a generator before the corpus can answer basic questions such as:

- Which official assets are the closest references?
- What texture size and UV density do they use?
- How thick are comparable straps, handles, limbs, ornaments, and trim pieces?
- Which details are modeled versus painted?
- Which defects can be detected automatically?

Rendering, generation, learned evaluation, and packaging should follow once the base corpus is reliable.
