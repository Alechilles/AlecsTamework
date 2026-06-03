(() => {
  const PLUGIN_ID = "tamework_spawner_icon_batch_renderer";
  const ACTION_ID = "tamework_run_spawner_icon_batch";
  const ACTION_WIZARD_ID = "tamework_generate_and_run_spawner_icon_batch";
  const EXPECTED_SCHEMA = "tamework.spawner-icon-render-jobs.v1";
  const EMPTY_OPTION_SENTINEL = "__empty__";

  let runAction = null;
  let wizardAction = null;
  let isRunning = false;
  let wizardLastValues = null;
  const compositedTextureCache = new Map();
  const textureVisibilityCache = new Map();
  const runDebugRows = [];
  const JOB_TIMEOUT_MS = 45000;

  function requireDesktopApp() {
    if (typeof isApp !== "undefined" && !isApp) {
      throw new Error("This plugin requires the Blockbench desktop app.");
    }
  }

  function getPathModule() {
    if (typeof PathModule !== "undefined") {
      return PathModule;
    }
    return require("path");
  }

  function getFsModule() {
    if (typeof requireNativeModule === "function") {
      return requireNativeModule("fs");
    }
    return require("fs");
  }

  function getBufferCtor() {
    if (typeof Buffer !== "undefined") {
      return Buffer;
    }
    return require("buffer").Buffer;
  }

  function asNumber(value, fallback) {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
  }

  function asArrayNumbers(value, expectedLength, fallback) {
    if (!Array.isArray(value) || value.length < expectedLength) {
      return fallback.slice();
    }
    const parsed = value.slice(0, expectedLength).map((entry, index) => {
      return asNumber(entry, fallback[index]);
    });
    return parsed;
  }

  function parseCsv(raw) {
    if (typeof raw !== "string") {
      return [];
    }
    return raw
      .split(",")
      .map((part) => part.trim())
      .filter((part) => part.length > 0);
  }

  function normalizeCaseKey(value) {
    return String(value || "").trim().toLowerCase();
  }

  function formatIntegerWithSeparators(value) {
    const raw =
      typeof value === "bigint"
        ? value.toString()
        : String(Math.trunc(asNumber(value, 0)));
    return raw.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }

  function resolveSetNameSelections(rawSelections, knownSetNames, fieldLabel) {
    const values = Array.isArray(rawSelections)
      ? rawSelections
          .map((entry) => String(entry || "").trim())
          .filter((entry) => entry.length > 0)
      : parseCsv(rawSelections);
    const lookup = new Map();
    (knownSetNames || []).forEach((setName) => {
      lookup.set(normalizeCaseKey(setName), setName);
    });
    const resolved = [];
    const seen = new Set();
    const unknown = [];
    values.forEach((entry) => {
      const key = normalizeCaseKey(entry);
      if (!key) {
        return;
      }
      const canonical = lookup.get(key);
      if (!canonical) {
        unknown.push(entry);
        return;
      }
      const canonicalKey = normalizeCaseKey(canonical);
      if (seen.has(canonicalKey)) {
        return;
      }
      seen.add(canonicalKey);
      resolved.push(canonical);
    });
    if (unknown.length) {
      throw new Error(
        `${fieldLabel} contains unknown set names: ${unknown.join(", ")}.\n`
        + `Available sets: ${(knownSetNames || []).join(", ")}`
      );
    }
    return resolved;
  }

  function slugify(value) {
    const slug = String(value || "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "");
    return slug || "value";
  }

  function safeKey(name) {
    return `set_${slugify(name).replace(/-/g, "_")}`;
  }

  function hasFieldTemplate(template) {
    return typeof template === "string" && template.includes("{") && template.includes("}");
  }

  function formatTemplate(template, placeholders) {
    if (!hasFieldTemplate(template)) {
      return template;
    }
    return template.replace(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (match, key) => {
      if (!(key in placeholders)) {
        throw new Error(`Template references unknown field: ${key}`);
      }
      return String(placeholders[key]);
    });
  }

  function cartesianProduct(lists) {
    if (!lists.length) {
      return [[]];
    }
    const [head, ...tail] = lists;
    const tailProduct = cartesianProduct(tail);
    const result = [];
    for (const item of head) {
      for (const row of tailProduct) {
        result.push([item, ...row]);
      }
    }
    return result;
  }

  function findAllPathSegmentIndexes(rawPath, marker) {
    const normalizedPath = String(rawPath || "");
    const normalizedMarker = String(marker || "");
    if (!normalizedPath || !normalizedMarker) {
      return [];
    }
    const pathLower = normalizedPath.toLowerCase();
    const markerLower = normalizedMarker.toLowerCase();
    const indexes = [];
    let searchFrom = 0;
    while (searchFrom < pathLower.length) {
      const index = pathLower.indexOf(markerLower, searchFrom);
      if (index === -1) {
        break;
      }
      indexes.push(index);
      searchFrom = index + markerLower.length;
    }
    return indexes;
  }

  function inferServerRootsFromPath(anyPath) {
    const raw = String(anyPath || "");
    if (!raw) {
      return [];
    }
    const path = getPathModule();
    const normalized = path.normalize(raw);
    const marker = `${path.sep}Server${path.sep}`;
    const roots = [];
    const seen = new Set();
    findAllPathSegmentIndexes(normalized, marker).forEach((index) => {
      const candidate = normalized.slice(0, index);
      const key = candidate.toLowerCase();
      if (!candidate || seen.has(key)) {
        return;
      }
      seen.add(key);
      roots.push(candidate);
    });
    return roots;
  }

  function inferModRootFromServerPath(serverPath) {
    const marker = `${getPathModule().sep}Server${getPathModule().sep}`;
    const serverRoots = inferServerRootsFromPath(serverPath);
    if (!serverRoots.length) {
      throw new Error(
        `Could not infer mod root from path (expected ...${marker}...): ${serverPath}`
      );
    }
    return serverRoots[serverRoots.length - 1];
  }

  function resolveCommonAssetFile(commonRoot, assetPath) {
    if (!assetPath || typeof assetPath !== "string") {
      return null;
    }
    return normalizePath(assetPath, commonRoot);
  }

  function inferGameRootFromPath(anyPath) {
    const raw = String(anyPath || "");
    if (!raw) {
      return null;
    }
    const path = getPathModule();
    const sep = path.sep;
    const lower = raw.toLowerCase();
    const markers = [
      `${sep}server${sep}mods${sep}`,
      `${sep}server${sep}`,
      `${sep}assets${sep}`,
      `${sep}common${sep}`
    ];
    for (const marker of markers) {
      const index = lower.indexOf(marker);
      if (index !== -1) {
        return raw.slice(0, index);
      }
    }
    return null;
  }

  function buildAssetSearchRoots(modRoot, sourcePaths) {
    const path = getPathModule();
    const roots = [];
    const seen = new Set();
    const addRoot = (candidate) => {
      if (!candidate || typeof candidate !== "string") {
        return;
      }
      const normalized = path.normalize(candidate);
      const key = normalized.toLowerCase();
      if (seen.has(key)) {
        return;
      }
      seen.add(key);
      roots.push(normalized);
    };

    addRoot(path.join(modRoot, "Common"));
    addRoot(path.join(modRoot, "Assets", "Common"));

    (sourcePaths || []).forEach((entry) => {
      inferServerRootsFromPath(entry).forEach((serverRoot) => {
        addRoot(path.join(serverRoot, "Common"));
        addRoot(path.join(serverRoot, "Assets", "Common"));
      });

      const gameRoot = inferGameRootFromPath(entry);
      if (gameRoot) {
        addRoot(path.join(gameRoot, "Common"));
        addRoot(path.join(gameRoot, "Assets", "Common"));
      }
    });

    return roots;
  }

  function resolveAssetFileFromRoots(assetRoots, assetPath) {
    if (!assetPath || typeof assetPath !== "string") {
      return null;
    }
    const roots = Array.isArray(assetRoots) && assetRoots.length ? assetRoots : [];
    let fallbackPath = null;
    for (const root of roots) {
      const candidate = normalizePath(assetPath, root);
      if (!candidate) {
        continue;
      }
      if (!fallbackPath) {
        fallbackPath = candidate;
      }
      if (fileExists(candidate)) {
        return candidate;
      }
    }
    return fallbackPath;
  }

  function inferModelName(modelPath) {
    const path = getPathModule();
    return path.basename(modelPath, path.extname(modelPath));
  }

  function writeJson(path, payload) {
    const fs = getFsModule();
    ensureDirectory(path);
    fs.writeFileSync(path, JSON.stringify(payload, null, 2) + "\n", "utf8");
  }

  function sanitizeFileToken(value, fallback) {
    const raw = String(value || "").trim();
    const sanitized = raw.replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^_+|_+$/g, "");
    return sanitized || (fallback || "value");
  }

  function copyDebugFileIntoDir(debugDir, sourcePath, label, copied) {
    if (!sourcePath || !fileExists(sourcePath)) {
      return null;
    }
    const path = getPathModule();
    const fs = getFsModule();
    const ext = path.extname(sourcePath) || ".dat";
    const base = path.basename(sourcePath, ext);
    const index = String((copied ? copied.length : 0) + 1).padStart(2, "0");
    const fileName = `${index}_${sanitizeFileToken(label, "file")}_${sanitizeFileToken(base, "source")}${ext}`;
    const outPath = path.join(debugDir, fileName);
    ensureDirectory(outPath);
    fs.copyFileSync(sourcePath, outPath);
    if (copied) {
      copied.push({
        label,
        sourcePath,
        copiedPath: outPath
      });
    }
    return outPath;
  }

  function buildVariantDebugSnapshotDir(jobsDir) {
    return getPathModule().join(jobsDir, ".tmp", "spawner_icon_variant_debug_last");
  }

  function captureVariantDebugSnapshot(job, debugRow, jobsDir) {
    const path = getPathModule();
    const fs = getFsModule();
    const debugDir = buildVariantDebugSnapshotDir(jobsDir);
    ensureDirectory(path.join(debugDir, "_placeholder.txt"));
    const copied = [];

    const baseModelPath = debugRow && debugRow.baseModelPath
      ? debugRow.baseModelPath
      : normalizePath(job && job.baseModelFile, jobsDir);
    const baseTexturePath = debugRow && debugRow.baseTexturePath
      ? debugRow.baseTexturePath
      : normalizePath(job && job.baseTextureFile, jobsDir);

    copyDebugFileIntoDir(debugDir, baseModelPath, "base_model", copied);
    copyDebugFileIntoDir(debugDir, baseTexturePath, "base_texture", copied);

    if (debugRow && typeof debugRow.compositedTexturePath === "string") {
      copyDebugFileIntoDir(debugDir, debugRow.compositedTexturePath, "composited_texture", copied);
    }
    if (debugRow && typeof debugRow.overrideTexturePath === "string") {
      copyDebugFileIntoDir(debugDir, debugRow.overrideTexturePath, "override_texture", copied);
    }

    const assets = debugRow && Array.isArray(debugRow.selectedOptionAssets)
      ? debugRow.selectedOptionAssets
      : Array.isArray(job && job.selectedOptionAssets)
      ? job.selectedOptionAssets
      : [];
    assets.forEach((asset, index) => {
      const setName = sanitizeFileToken(asset && asset.set, `set${index + 1}`);
      const optionName = sanitizeFileToken(asset && asset.option, `option${index + 1}`);
      copyDebugFileIntoDir(
        debugDir,
        asset && asset.modelFile ? normalizePath(asset.modelFile, jobsDir) : null,
        `${setName}_${optionName}_model`,
        copied
      );
      copyDebugFileIntoDir(
        debugDir,
        asset && asset.textureFile ? normalizePath(asset.textureFile, jobsDir) : null,
        `${setName}_${optionName}_texture`,
        copied
      );
    });

    const textureCatalogBuckets = [];
    if (debugRow && Array.isArray(debugRow.textureCatalogBeforeAttachments)) {
      textureCatalogBuckets.push({
        label: "catalog_before",
        entries: debugRow.textureCatalogBeforeAttachments
      });
    }
    if (debugRow && Array.isArray(debugRow.textureCatalogAfterAttachments)) {
      textureCatalogBuckets.push({
        label: "catalog_after_attachments",
        entries: debugRow.textureCatalogAfterAttachments
      });
    }
    if (debugRow && Array.isArray(debugRow.textureCatalogAfterBaseOverride)) {
      textureCatalogBuckets.push({
        label: "catalog_after_override",
        entries: debugRow.textureCatalogAfterBaseOverride
      });
    }
    const copiedTexturePaths = new Set();
    textureCatalogBuckets.forEach((bucket) => {
      (bucket.entries || []).forEach((entry) => {
        if (!entry || typeof entry.path !== "string" || !entry.path.trim()) {
          return;
        }
        const normalized = normalizeForCompare(entry.path);
        if (!normalized || copiedTexturePaths.has(normalized) || !fileExists(entry.path)) {
          return;
        }
        copiedTexturePaths.add(normalized);
        const entryLabel = sanitizeFileToken(entry.name || entry.id || `texture_${entry.index}`, "texture");
        copyDebugFileIntoDir(
          debugDir,
          entry.path,
          `${bucket.label}_${entryLabel}`,
          copied
        );
      });
    });

    const outputIcon = normalizePath(job && job.outputIconFile, jobsDir);
    copyDebugFileIntoDir(debugDir, outputIcon, "output_icon", copied);

    const metadata = {
      schema: "tamework.spawner-icon-variant-debug.v1",
      generatedAtUtc: new Date().toISOString(),
      jobId: job && typeof job.id === "string" ? job.id : null,
      jobsDir,
      debugDir,
      copiedFiles: copied,
      job,
      debugRow: debugRow || null
    };
    writeJson(path.join(debugDir, "snapshot_manifest.json"), metadata);
    return debugDir;
  }

  function getActiveProjectTexture() {
    if (typeof Texture === "undefined" || !Array.isArray(Texture.all) || !Texture.all.length) {
      return null;
    }
    return Texture.all.find((entry) => entry && entry.use_as_default) || Texture.all[0] || null;
  }

  function ensurePngFromDataUrl(dataUrl, jobsDir, label) {
    if (typeof dataUrl !== "string" || !dataUrl.startsWith("data:image/")) {
      return null;
    }
    const comma = dataUrl.indexOf(",");
    if (comma < 0) {
      return null;
    }
    const base64 = dataUrl.slice(comma + 1);
    if (!base64) {
      return null;
    }
    const outDir = getPathModule().join(jobsDir, ".tmp", "bb_icon_texture_cache");
    const hash = hashString(`${label || "texture"}:${base64.slice(0, 256)}:${base64.length}`);
    const outPath = getPathModule().join(outDir, `${label || "texture"}_${hash}.png`);
    if (fileExists(outPath)) {
      return outPath;
    }
    ensureDirectory(outPath);
    getFsModule().writeFileSync(outPath, getBufferCtor().from(base64, "base64"));
    return outPath;
  }

  function resolveProjectTexturePath(jobsDir, label) {
    const texture = getActiveProjectTexture();
    if (!texture) {
      return null;
    }
    if (texture.path && fileExists(texture.path)) {
      return texture.path;
    }
    if (texture.source) {
      const fromSource = ensurePngFromDataUrl(texture.source, jobsDir, label || "project_default");
      if (fromSource) {
        return fromSource;
      }
    }
    if (texture.img && typeof texture.img.src === "string") {
      const fromImg = ensurePngFromDataUrl(texture.img.src, jobsDir, label || "project_default");
      if (fromImg) {
        return fromImg;
      }
    }
    if (texture.canvas && typeof texture.canvas.toDataURL === "function") {
      const fromCanvas = ensurePngFromDataUrl(
        texture.canvas.toDataURL("image/png"),
        jobsDir,
        label || "project_default"
      );
      if (fromCanvas) {
        return fromCanvas;
      }
    }
    return null;
  }

  function replaceFileExt(path, ext) {
    const parsed = getPathModule().parse(path);
    return getPathModule().join(parsed.dir, parsed.name + ext);
  }

  function normalizePath(rawPath, baseDir) {
    if (typeof rawPath !== "string" || !rawPath.trim()) {
      return null;
    }
    const path = getPathModule();
    if (path.isAbsolute(rawPath)) {
      return rawPath;
    }
    return path.resolve(baseDir, rawPath);
  }

  function readJsonFromDisk(filePath) {
    const fs = getFsModule();
    const content = fs.readFileSync(filePath, "utf8");
    if (typeof autoParseJSON === "function") {
      return autoParseJSON(content);
    }
    return JSON.parse(content);
  }

  function fileExists(filePath) {
    const fs = getFsModule();
    try {
      return fs.existsSync(filePath);
    } catch (_error) {
      return false;
    }
  }

  function ensureDirectory(filePath) {
    const fs = getFsModule();
    const path = getPathModule();
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
  }

  function normalizeForCompare(filePath) {
    if (!filePath || typeof filePath !== "string") {
      return "";
    }
    return getPathModule().normalize(filePath).toLowerCase();
  }

  function samePath(a, b) {
    const na = normalizeForCompare(a);
    const nb = normalizeForCompare(b);
    return !!na && !!nb && na === nb;
  }

  function waitFrame() {
    return new Promise((resolve) => {
      setTimeout(resolve, 0);
    });
  }

  function withTimeout(promise, timeoutMs, label) {
    return new Promise((resolve, reject) => {
      let done = false;
      const timer = setTimeout(() => {
        if (done) {
          return;
        }
        done = true;
        reject(new Error(`Timed out after ${timeoutMs}ms while rendering ${label}.`));
      }, timeoutMs);
      Promise.resolve(promise)
        .then((value) => {
          if (done) {
            return;
          }
          done = true;
          clearTimeout(timer);
          resolve(value);
        })
        .catch((error) => {
          if (done) {
            return;
          }
          done = true;
          clearTimeout(timer);
          reject(error);
        });
    });
  }

  function getOpenModelProjects() {
    if (typeof ModelProject === "undefined" || !Array.isArray(ModelProject.all)) {
      return [];
    }
    return ModelProject.all.filter((project) => project && typeof project === "object");
  }

  function getActiveModelProject() {
    return typeof Project !== "undefined" && Project && typeof Project === "object" ? Project : null;
  }

  function getNewModelProject(beforeProjects, previousProject) {
    const before = new Set(beforeProjects || []);
    const currentProject = getActiveModelProject();
    if (currentProject && currentProject !== previousProject && !before.has(currentProject)) {
      return currentProject;
    }
    return getOpenModelProjects().find((project) => !before.has(project)) || null;
  }

  async function closeManagedModelProject(project) {
    if (!project || typeof project.close !== "function") {
      return false;
    }
    try {
      project.saved = true;
      await project.close(true);
      await waitFrame();
      return true;
    } catch (error) {
      console.warn(`[${PLUGIN_ID}] Failed to close temporary Blockbench project`, error);
      return false;
    }
  }

  function getTextureByPath(texturePath) {
    if (typeof Texture === "undefined" || !Array.isArray(Texture.all)) {
      return null;
    }
    return Texture.all.find((texture) => texture && texture.path === texturePath) || null;
  }

  function readPngDimensions(texturePath) {
    if (!texturePath || !fileExists(texturePath)) {
      return null;
    }
    try {
      const bytes = getFsModule().readFileSync(texturePath);
      if (!bytes || bytes.length < 24) {
        return null;
      }
      const signature = [137, 80, 78, 71, 13, 10, 26, 10];
      for (let i = 0; i < signature.length; i += 1) {
        if (bytes[i] !== signature[i]) {
          return null;
        }
      }
      const chunk = bytes.toString("ascii", 12, 16);
      if (chunk !== "IHDR") {
        return null;
      }
      const width = bytes.readUInt32BE(16);
      const height = bytes.readUInt32BE(20);
      if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
        return null;
      }
      return {
        width,
        height
      };
    } catch (_error) {
      return null;
    }
  }

  function updateTextureUvSize(texture, texturePath) {
    if (!texture || typeof texture !== "object") {
      return null;
    }
    const pngSize = readPngDimensions(texturePath || texture.path || "");
    let width = pngSize ? pngSize.width : asNumber(texture.width, 0);
    let height = pngSize ? pngSize.height : asNumber(texture.height, 0);
    if (!(width > 0) && Number.isFinite(texture.uv_width) && texture.uv_width > 0) {
      width = Number(texture.uv_width);
    }
    if (!(height > 0) && Number.isFinite(texture.uv_height) && texture.uv_height > 0) {
      height = Number(texture.uv_height);
    }
    if (width > 0) {
      texture.uv_width = width;
    }
    if (height > 0) {
      texture.uv_height = height;
    }
    return width > 0 && height > 0
      ? {
          width,
          height
        }
      : null;
  }

  function loadTextureFromPath(texturePath) {
    if (!texturePath || !fileExists(texturePath)) {
      return null;
    }
    let texture = getTextureByPath(texturePath);
    if (!texture) {
      texture = new Texture().fromPath(texturePath).add(false, true);
    }
    updateTextureUvSize(texture, texturePath);
    return texture;
  }

  function setDefaultTexture(texturePath) {
    const texture = loadTextureFromPath(texturePath);
    if (!texture) {
      return null;
    }
    if (typeof Texture !== "undefined" && Array.isArray(Texture.all)) {
      Texture.all.forEach((entry) => {
        if (entry) {
          entry.use_as_default = false;
        }
      });
    }
    texture.use_as_default = true;
    return texture;
  }

  function normalizeTextureKey(key) {
    if (key === null || key === false || typeof key === "undefined") {
      return null;
    }
    return String(key);
  }

  function getTextureCatalog() {
    if (typeof Texture === "undefined" || !Array.isArray(Texture.all)) {
      return [];
    }
    return Texture.all.map((texture, index) => {
      if (!texture) {
        return null;
      }
      return {
        index,
        id: typeof texture.id === "string" ? texture.id : null,
        uuid: texture.uuid ? String(texture.uuid) : null,
        name: typeof texture.name === "string" ? texture.name : null,
        path: typeof texture.path === "string" ? texture.path : null,
        width: Number.isFinite(texture.width) ? Number(texture.width) : null,
        height: Number.isFinite(texture.height) ? Number(texture.height) : null,
        useAsDefault: !!texture.use_as_default
      };
    }).filter((entry) => !!entry);
  }

  function collectTextureAliasKeys(texture, explicitIndex) {
    const keys = new Set();
    if (!texture || typeof texture !== "object") {
      return keys;
    }
    const push = (value) => {
      const key = normalizeTextureKey(value);
      if (key) {
        keys.add(key);
      }
    };
    push(texture.uuid);
    push(texture.id);
    let index = Number.isInteger(explicitIndex) ? explicitIndex : -1;
    if (index < 0 && typeof Texture !== "undefined" && Array.isArray(Texture.all)) {
      index = Texture.all.indexOf(texture);
    }
    if (index >= 0) {
      keys.add(String(index));
      keys.add(`#${index}`);
    }
    return keys;
  }

  function buildTextureAliasMap() {
    const map = new Map();
    if (typeof Texture === "undefined" || !Array.isArray(Texture.all)) {
      return map;
    }
    Texture.all.forEach((texture, index) => {
      if (!texture || !texture.uuid) {
        return;
      }
      const canonical = String(texture.uuid);
      const aliases = collectTextureAliasKeys(texture, index);
      aliases.forEach((alias) => {
        if (!map.has(alias)) {
          map.set(alias, canonical);
        }
      });
    });
    return map;
  }

  function resolveElementFromInput(entry) {
    if (!entry) {
      return null;
    }
    if (entry.faces) {
      return entry;
    }
    return resolveOutlinerRef(entry);
  }

  function collectFaceTextureUsage(scopeElements) {
    const counts = new Map();
    if (typeof Cube === "undefined" || !Array.isArray(Cube.all)) {
      return counts;
    }
    const source = Array.isArray(scopeElements) && scopeElements.length
      ? scopeElements
      : Cube.all;
    for (const raw of source) {
      const cube = resolveElementFromInput(raw);
      if (!cube || !cube.faces) {
        continue;
      }
      for (const faceKey of Object.keys(cube.faces)) {
        const face = cube.faces[faceKey];
        if (!face) {
          continue;
        }
        const key = normalizeTextureKey(face.texture);
        if (!key) {
          continue;
        }
        counts.set(key, (counts.get(key) || 0) + 1);
      }
    }
    return counts;
  }

  function summarizeFaceTextureUsage(scopeElements, limit) {
    const maxRows = Number.isFinite(limit) ? Math.max(1, Math.floor(limit)) : 24;
    const aliasMap = buildTextureAliasMap();
    const catalog = getTextureCatalog();
    const byUuid = new Map();
    catalog.forEach((entry) => {
      if (entry && entry.uuid) {
        byUuid.set(entry.uuid, entry);
      }
    });
    const rows = Array.from(collectFaceTextureUsage(scopeElements).entries())
      .map(([key, count]) => {
        const resolvedUuid = aliasMap.get(key) || null;
        const texture = resolvedUuid ? byUuid.get(resolvedUuid) : null;
        return {
          key,
          count,
          resolvedUuid,
          resolvedPath: texture && texture.path ? texture.path : null,
          resolvedName: texture && texture.name ? texture.name : null
        };
      })
      .sort((a, b) => b.count - a.count);
    return rows.slice(0, maxRows);
  }

  function detectPrimaryTextureKey(baseTexturePath) {
    const textures = typeof Texture !== "undefined" && Array.isArray(Texture.all) ? Texture.all : [];
    const normalizedBasePath = normalizeForCompare(baseTexturePath);
    if (normalizedBasePath) {
      const byPath = textures.find((entry) => {
        return entry && typeof entry.path === "string" && samePath(entry.path, baseTexturePath);
      });
      if (byPath && byPath.uuid) {
        return String(byPath.uuid);
      }
    }

    const faceUsage = collectFaceTextureUsage();
    if (!faceUsage.size) {
      return null;
    }

    let bestKey = null;
    let bestCount = -1;
    for (const [key, count] of faceUsage.entries()) {
      if (count > bestCount) {
        bestCount = count;
        bestKey = key;
      }
    }
    return bestKey;
  }

  function remapFaceTextureKeys(fromKeys, toKey, scopeElements) {
    const fromSet = new Set();
    if (Array.isArray(fromKeys)) {
      fromKeys.forEach((entry) => {
        const normalized = normalizeTextureKey(entry);
        if (normalized) {
          fromSet.add(normalized);
        }
      });
    } else {
      const normalized = normalizeTextureKey(fromKeys);
      if (normalized) {
        fromSet.add(normalized);
      }
    }
    const to = normalizeTextureKey(toKey);
    if (!fromSet.size || !to) {
      return 0;
    }
    if (typeof Cube === "undefined" || !Array.isArray(Cube.all)) {
      return 0;
    }
    const source = Array.isArray(scopeElements) && scopeElements.length
      ? scopeElements
      : Cube.all;
    let remapped = 0;
    for (const raw of source) {
      const cube = resolveElementFromInput(raw);
      if (!cube || !cube.faces) {
        continue;
      }
      for (const faceKey of Object.keys(cube.faces)) {
        const face = cube.faces[faceKey];
        if (!face) {
          continue;
        }
        const faceKeyNorm = normalizeTextureKey(face.texture);
        if (!faceKeyNorm || faceKeyNorm === to) {
          continue;
        }
        if (fromSet.has(faceKeyNorm)) {
          face.texture = to;
          remapped += 1;
        }
      }
    }
    return remapped;
  }

  function remapFaceTextureKey(fromKey, toKey, scopeElements) {
    return remapFaceTextureKeys([fromKey], toKey, scopeElements);
  }

  function resolveOutlinerRef(ref) {
    if (!ref) {
      return null;
    }
    if (typeof ref === "object") {
      return ref;
    }
    if (typeof ref !== "string") {
      return null;
    }
    if (typeof OutlinerNode !== "undefined" && OutlinerNode && OutlinerNode.uuids) {
      const direct = OutlinerNode.uuids[ref];
      if (direct) {
        return direct;
      }
    }
    if (typeof Group !== "undefined" && Array.isArray(Group.all)) {
      const group = Group.all.find((entry) => entry && entry.uuid === ref);
      if (group) {
        return group;
      }
    }
    if (typeof Cube !== "undefined" && Array.isArray(Cube.all)) {
      const cube = Cube.all.find((entry) => entry && entry.uuid === ref);
      if (cube) {
        return cube;
      }
    }
    return null;
  }

  function walkOutlinerTree(rootRefs, visit) {
    const stack = Array.isArray(rootRefs) ? rootRefs.slice() : [];
    const seen = new Set();
    while (stack.length) {
      const next = stack.pop();
      const node = resolveOutlinerRef(next);
      if (!node || !node.uuid) {
        continue;
      }
      if (seen.has(node.uuid)) {
        continue;
      }
      seen.add(node.uuid);
      visit(node);
      const children = Array.isArray(node.children) ? node.children : [];
      for (const child of children) {
        stack.push(child);
      }
    }
  }

  function getAttachmentElements(content, rootRefs) {
    const elements = [];
    const seen = new Set();
    const pushElement = (element) => {
      if (!element || !element.faces) {
        return;
      }
      const key = element.uuid || element.id || element.name || String(elements.length);
      if (seen.has(key)) {
        return;
      }
      seen.add(key);
      elements.push(element);
    };
    walkOutlinerTree(rootRefs, (node) => {
      if (node && node.faces) {
        pushElement(node);
      }
    });
    if (!elements.length && content && Array.isArray(content.new_cubes)) {
      for (const cube of content.new_cubes) {
        pushElement(cube);
      }
    }
    if (!elements.length && content && Array.isArray(content.new_elements)) {
      for (const element of content.new_elements) {
        pushElement(element);
      }
    }
    return elements;
  }

  function collectAttachmentSourceTextureKeys(content) {
    const keys = new Set();
    if (!content || !Array.isArray(content.new_textures)) {
      return keys;
    }
    content.new_textures.forEach((entry, index) => {
      if (!entry) {
        return;
      }
      const byUuid = normalizeTextureKey(entry.uuid);
      if (byUuid) {
        keys.add(byUuid);
      }
      const byId = normalizeTextureKey(entry.id);
      if (byId) {
        keys.add(byId);
      }
      keys.add(String(index));
    });
    return keys;
  }

  function remapAttachmentElementTextures(content, targetTexture, rootRefs) {
    const result = {
      elements: 0,
      remappedFaces: 0,
      explicitHiddenFaces: 0,
      nullFacesLeftUntouched: 0,
      untouchedFaces: 0,
      faceUsageBefore: [],
      faceUsageAfter: []
    };
    if (!targetTexture || !targetTexture.uuid) {
      return result;
    }
    const targetKey = String(targetTexture.uuid);
    const elements = getAttachmentElements(content, rootRefs);
    result.elements = elements.length;
    result.faceUsageBefore = summarizeFaceTextureUsage(elements, 18);
    for (const element of elements) {
      const faces = element && element.faces ? element.faces : null;
      if (!faces) {
        continue;
      }
      for (const faceKey of Object.keys(faces)) {
        const face = faces[faceKey];
        if (!face) {
          continue;
        }
        const sourceKey = normalizeTextureKey(face.texture);
        if (!sourceKey) {
          const explicitHidden = face.texture === false || face.enabled === false;
          if (explicitHidden) {
            if (face.texture !== false) {
              face.texture = false;
            }
            result.explicitHiddenFaces += 1;
            continue;
          }
          result.nullFacesLeftUntouched += 1;
          continue;
        }
        if (sourceKey !== targetKey) {
          face.texture = targetTexture.uuid;
          result.remappedFaces += 1;
        } else {
          result.untouchedFaces += 1;
        }
      }
    }
    result.faceUsageAfter = summarizeFaceTextureUsage(elements, 18);
    // Backward-compatible alias used by older debug readers.
    result.hiddenFacesDisabled = result.explicitHiddenFaces;
    result.boundFromNullFaces = 0;
    return result;
  }

  function ensureFacesTextured(texture) {
    if (!texture || !texture.uuid || typeof Cube === "undefined" || !Array.isArray(Cube.all)) {
      return;
    }
    let anyTexturedFace = false;
    for (const cube of Cube.all) {
      if (!cube || !cube.faces) {
        continue;
      }
      for (const key of Object.keys(cube.faces)) {
        const face = cube.faces[key];
        if (!face) {
          continue;
        }
        if (face.texture !== null && face.texture !== false && typeof face.texture !== "undefined") {
          anyTexturedFace = true;
          break;
        }
      }
      if (anyTexturedFace) {
        break;
      }
    }
    if (anyTexturedFace) {
      return;
    }
    for (const cube of Cube.all) {
      if (!cube || !cube.faces) {
        continue;
      }
      for (const key of Object.keys(cube.faces)) {
        const face = cube.faces[key];
        if (!face) {
          continue;
        }
        if (face.texture === null || face.texture === false || typeof face.texture === "undefined") {
          face.texture = texture.uuid;
        }
      }
    }
  }

  function faceHasRenderableTexture(face) {
    if (!face) {
      return false;
    }
    const key = normalizeTextureKey(face.texture);
    if (!key) {
      return false;
    }
    if (!Array.isArray(face.uv) || face.uv.length < 4) {
      return true;
    }
    const width = Math.abs(asNumber(face.uv[2], 0) - asNumber(face.uv[0], 0));
    const height = Math.abs(asNumber(face.uv[3], 0) - asNumber(face.uv[1], 0));
    return width > 0.0001 && height > 0.0001;
  }

  function pickQuadNormalFace(cube) {
    if (!cube || !cube.faces) {
      return null;
    }
    const priority = ["south", "north", "east", "west", "up", "down"];
    for (const faceName of priority) {
      if (faceHasRenderableTexture(cube.faces[faceName])) {
        return faceName;
      }
    }
    return null;
  }

  function clearZeroAreaUvFaces() {
    if (typeof Cube === "undefined" || !Array.isArray(Cube.all)) {
      return { checkedFaces: 0, clearedFaces: 0 };
    }
    let checkedFaces = 0;
    let clearedFaces = 0;
    for (const cube of Cube.all) {
      if (!cube || !cube.faces) {
        continue;
      }
      for (const faceName of Object.keys(cube.faces)) {
        const face = cube.faces[faceName];
        if (!face) {
          continue;
        }
        const textureKey = normalizeTextureKey(face.texture);
        if (!textureKey) {
          continue;
        }
        if (!Array.isArray(face.uv) || face.uv.length < 4) {
          continue;
        }
        checkedFaces += 1;
        const uvWidth = Math.abs(asNumber(face.uv[2], 0) - asNumber(face.uv[0], 0));
        const uvHeight = Math.abs(asNumber(face.uv[3], 0) - asNumber(face.uv[1], 0));
        if (uvWidth <= 0.0001 || uvHeight <= 0.0001) {
          face.texture = null;
          clearedFaces += 1;
        }
      }
    }
    if (clearedFaces > 0 && typeof Canvas !== "undefined" && typeof Canvas.updateView === "function") {
      Canvas.updateView({
        elements: Cube.all,
        element_aspects: { faces: true, geometry: true }
      });
    }
    return { checkedFaces, clearedFaces };
  }

  function stabilizeQuadDepth(depthOffset) {
    if (typeof Cube === "undefined" || !Array.isArray(Cube.all)) {
      return 0;
    }
    const offset = Math.max(0, asNumber(depthOffset, 0.06));
    if (!(offset > 0)) {
      return 0;
    }
    const faceToVector = {
      south: [0, 0, 1],
      north: [0, 0, -1],
      east: [1, 0, 0],
      west: [-1, 0, 0],
      up: [0, 1, 0],
      down: [0, -1, 0]
    };
    let adjusted = 0;
    for (const cube of Cube.all) {
      if (!cube || !Array.isArray(cube.from) || !Array.isArray(cube.to)) {
        continue;
      }
      const dx = Math.abs(asNumber(cube.to[0], 0) - asNumber(cube.from[0], 0));
      const dy = Math.abs(asNumber(cube.to[1], 0) - asNumber(cube.from[1], 0));
      const dz = Math.abs(asNumber(cube.to[2], 0) - asNumber(cube.from[2], 0));
      const zeroAxes = [dx < 0.0001, dy < 0.0001, dz < 0.0001];
      const zeroCount = zeroAxes.filter((entry) => entry).length;
      if (zeroCount !== 1) {
        continue;
      }
      const normalFace = pickQuadNormalFace(cube);
      const vector = normalFace ? faceToVector[normalFace] : null;
      if (!vector) {
        continue;
      }
      cube.from[0] += vector[0] * offset;
      cube.from[1] += vector[1] * offset;
      cube.from[2] += vector[2] * offset;
      cube.to[0] += vector[0] * offset;
      cube.to[1] += vector[1] * offset;
      cube.to[2] += vector[2] * offset;
      if (Array.isArray(cube.origin) && cube.origin.length >= 3) {
        cube.origin[0] += vector[0] * offset;
        cube.origin[1] += vector[1] * offset;
        cube.origin[2] += vector[2] * offset;
      }
      adjusted += 1;
    }
    if (adjusted > 0 && typeof Canvas !== "undefined" && typeof Canvas.updateView === "function") {
      Canvas.updateView({
        elements: Cube.all,
        element_aspects: { transform: true, geometry: true, faces: true }
      });
    }
    return adjusted;
  }

  function pickJsonFile(startPath) {
    return new Promise((resolve) => {
      Filesystem.importFile(
        {
          extensions: ["json"],
          type: "JSON",
          multiple: false,
          startpath: startPath || ""
        },
        (files) => {
          resolve(files && files.length ? files[0] : null);
        }
      );
    });
  }

  function showError(error) {
    const message = error && error.message ? error.message : String(error);
    return showTextDialog("Spawner Icon Batch Renderer", message, 980);
  }

  function parseJobsPayload(payload) {
    if (!payload || typeof payload !== "object") {
      throw new Error("Jobs payload is not a JSON object.");
    }
    if (payload.schema && payload.schema !== EXPECTED_SCHEMA) {
      throw new Error(
        `Unsupported jobs schema: ${payload.schema}. Expected ${EXPECTED_SCHEMA}.`
      );
    }
    if (!Array.isArray(payload.jobs) || payload.jobs.length === 0) {
      throw new Error("Jobs payload does not contain a non-empty jobs array.");
    }
    return payload;
  }

  function extractSetDefinitions(modelJson, includeEmptySets, excludeSets) {
    const randomSets = modelJson && modelJson.RandomAttachmentSets;
    if (!randomSets || typeof randomSets !== "object") {
      if ((includeEmptySets && includeEmptySets.length) || (excludeSets && excludeSets.length)) {
        throw new Error("Model JSON does not define RandomAttachmentSets.");
      }
      return [];
    }
    const includeSet = new Set(includeEmptySets || []);
    const excludeSet = new Set(excludeSets || []);
    const result = [];
    Object.keys(randomSets).forEach((setName) => {
      if (excludeSet.has(setName)) {
        return;
      }
      const optionsObj = randomSets[setName];
      if (!optionsObj || typeof optionsObj !== "object") {
        throw new Error(`RandomAttachmentSets.${setName} is not an object.`);
      }
      const options = Object.keys(optionsObj);
      if (!options.length) {
        throw new Error(`RandomAttachmentSets.${setName} has no options.`);
      }
      if (includeSet.has(setName)) {
        options.push(EMPTY_OPTION_SENTINEL);
      }
      result.push({
        name: setName,
        options,
        includesEmpty: includeSet.has(setName)
      });
    });
    return result;
  }

  function resolveSetSelectionConfig(modelJson, includeEmptySetsCsv, excludeSetsCsv) {
    const allSetDefs = extractSetDefinitions(modelJson, [], []);
    const knownSetNames = allSetDefs.map((def) => def.name);
    const includeSelections = resolveSetNameSelections(
      includeEmptySetsCsv,
      knownSetNames,
      "Include Empty Sets CSV"
    );
    const excludeSelections = resolveSetNameSelections(
      excludeSetsCsv,
      knownSetNames,
      "Exclude Sets CSV"
    );
    const excludedLookup = new Set(excludeSelections.map((setName) => normalizeCaseKey(setName)));
    const includeIgnoredBecauseExcluded = includeSelections.filter((setName) => {
      return excludedLookup.has(normalizeCaseKey(setName));
    });
    const includeEffective = includeSelections.filter((setName) => {
      return !excludedLookup.has(normalizeCaseKey(setName));
    });
    const activeSetDefs = extractSetDefinitions(modelJson, includeEffective, excludeSelections);
    return {
      allSetDefs,
      activeSetDefs,
      includeEmptySets: includeEffective,
      excludedSets: excludeSelections,
      includeIgnoredBecauseExcluded
    };
  }

  function calculateComboCountFromSetDefinitions(setDefs) {
    let total = 1n;
    (setDefs || []).forEach((setDef) => {
      const optionCount = Array.isArray(setDef && setDef.options) ? setDef.options.length : 0;
      total *= BigInt(Math.max(1, optionCount));
    });
    return total;
  }

  function extractOptionVisuals(modelJson) {
    const randomSets = modelJson && modelJson.RandomAttachmentSets;
    const result = {};
    if (!randomSets || typeof randomSets !== "object") {
      return result;
    }
    Object.keys(randomSets).forEach((setName) => {
      const optionsObj = randomSets[setName];
      const setOut = {};
      if (!optionsObj || typeof optionsObj !== "object") {
        result[setName] = setOut;
        return;
      }
      Object.keys(optionsObj).forEach((optionName) => {
        const value = optionsObj[optionName];
        setOut[optionName] = {
          model: value && typeof value.Model === "string" ? value.Model : null,
          texture: value && typeof value.Texture === "string" ? value.Texture : null,
          weight:
            value && (typeof value.Weight === "number" || typeof value.Weight === "string")
              ? Number(value.Weight)
              : null
        };
      });
      result[setName] = setOut;
    });
    return result;
  }

  function discoverRolesFromSpawner(spawnerJson) {
    const allowed = spawnerJson && spawnerJson.AllowedRoles;
    if (!allowed || typeof allowed !== "object") {
      return [];
    }
    if (allowed.Mode !== "Allowlist" || !Array.isArray(allowed.Allowlist)) {
      return [];
    }
    return allowed.Allowlist.filter((entry) => typeof entry === "string" && entry.trim().length > 0);
  }

  function mergeOverridesIntoSpawner(spawnerJson, roleOverrides, iconOverrideGroups, iconDefault) {
    const output = Object.assign({}, spawnerJson || {});
    const existing =
      output.IconOverridesByRole && typeof output.IconOverridesByRole === "object"
        ? output.IconOverridesByRole
        : {};
    const merged = Object.assign({}, existing);
    Object.keys(roleOverrides).forEach((role) => {
      merged[role] = roleOverrides[role];
    });
    if (Object.keys(merged).length) {
      output.IconOverridesByRole = merged;
    } else {
      delete output.IconOverridesByRole;
    }
    const existingGroups = Array.isArray(output.IconOverrideGroups)
      ? output.IconOverrideGroups.slice()
      : [];
    const generatedGroups = Array.isArray(iconOverrideGroups) ? iconOverrideGroups : [];
    if (generatedGroups.length) {
      generatedGroups.forEach((group) => {
        replaceOrAppendIconOverrideGroup(existingGroups, group);
      });
      output.IconOverrideGroups = existingGroups;
    } else if (!existingGroups.length) {
      delete output.IconOverrideGroups;
    }
    if (typeof iconDefault === "string" && iconDefault.trim().length) {
      output.IconDefault = iconDefault.trim();
    }
    return output;
  }

  function iconOverrideGroupRoleKey(group) {
    if (!group || !Array.isArray(group.Roles)) {
      return "";
    }
    return group.Roles
      .filter((role) => typeof role === "string" && role.trim().length)
      .map((role) => role.trim().toLowerCase())
      .sort()
      .join("\u0000");
  }

  function replaceOrAppendIconOverrideGroup(groups, group) {
    const roleKey = iconOverrideGroupRoleKey(group);
    if (!roleKey) {
      return;
    }
    const replacement = Object.assign({}, group);
    const existingIndex = groups.findIndex(
      (existing) => iconOverrideGroupRoleKey(existing) === roleKey
    );
    if (existingIndex >= 0) {
      groups[existingIndex] = replacement;
      return;
    }
    groups.push(replacement);
  }

  function composeIconRelativePath(iconRelDir, filename) {
    const dir = String(iconRelDir || "").replace(/\\/g, "/").replace(/\/+$/g, "");
    const file = String(filename || "").replace(/\\/g, "/").replace(/^\/+/g, "");
    if (!dir) {
      return file;
    }
    return `${dir}/${file}`;
  }

  function buildGeneratedPayload(config) {
    const modelJson = config.modelJson;
    const modelPath = config.modelPath;
    const modRoot = config.modRoot;
    const commonRoot = config.commonRoot;
    const assetRoots =
      Array.isArray(config.assetRoots) && config.assetRoots.length
        ? config.assetRoots.slice()
        : [commonRoot];
    const roles = config.roles;
    const sharedRoleGroup = config.sharedRoleGroup === true && roles.length > 0;
    const sharedIconRole = roles[0];
    const setDefs = extractSetDefinitions(modelJson, config.includeEmptySets, config.excludeSets);
    const optionVisuals = extractOptionVisuals(modelJson);
    const baseModel = typeof modelJson.Model === "string" ? modelJson.Model : null;
    const baseTexture = typeof modelJson.Texture === "string" ? modelJson.Texture : null;

    const optionSpace = setDefs.map((setDef) => setDef.options);
    const combos = config.previewOnlyFirstCombo
      ? [optionSpace.map((options) => options[0])]
      : cartesianProduct(optionSpace);
    const roleOverrides = {};
    roles.forEach((role) => {
      if (!sharedRoleGroup) {
        roleOverrides[role] = [];
      }
    });
    const sharedOverrides = [];
    let sharedIconDefault = null;

    const jobs = [];
    const jobsByOutputPath = new Map();
    const manifestCombos = [];
    const modelName = inferModelName(modelPath);

    combos.forEach((combo, comboIdx) => {
      const comboIndex = comboIdx + 1;
      const attachments = {};
      const setValues = {};
      const slugParts = [];
      setDefs.forEach((setDef, i) => {
        const selected = combo[i];
        const rendered = selected === EMPTY_OPTION_SENTINEL ? config.emptyValueToken : selected;
        setValues[setDef.name] = rendered;
        slugParts.push(`${slugify(setDef.name)}-${slugify(rendered)}`);
        if (selected !== EMPTY_OPTION_SENTINEL) {
          attachments[setDef.name] = selected;
        }
      });
      const comboSlug = slugParts.length ? slugParts.join("__") : "base";

      const commonPlaceholders = {
        model: modelName,
        combo_index: String(comboIndex),
        combo_slug: comboSlug
      };
      Object.keys(setValues).forEach((setName) => {
        const value = setValues[setName];
        commonPlaceholders[safeKey(setName)] = value;
      });

      const selectedOptionAssets = [];
      Object.keys(attachments).forEach((setName) => {
        const optionName = attachments[setName];
        const visual =
          optionVisuals &&
          optionVisuals[setName] &&
          optionVisuals[setName][optionName]
            ? optionVisuals[setName][optionName]
            : null;
        selectedOptionAssets.push({
          set: setName,
          option: optionName,
          model: visual ? visual.model : null,
          texture: visual ? visual.texture : null,
          weight: visual ? visual.weight : null,
          modelFile: resolveAssetFileFromRoots(assetRoots, visual ? visual.model : null),
          textureFile: resolveAssetFileFromRoots(assetRoots, visual ? visual.texture : null)
        });
      });

      const iconsByRole = {};
      const addRenderJob = (role, iconRel, iconFile) => {
        const outputKey = normalizeForCompare(iconFile || iconRel);
        if (!jobsByOutputPath.has(outputKey)) {
          const jobPayload = {
            id: `${comboSlug}__role_${role}`,
            role,
            comboIndex,
            comboSlug,
            attachments,
            setValues,
            baseModel,
            baseTexture,
            baseModelFile: resolveAssetFileFromRoots(assetRoots, baseModel),
            baseTextureFile: resolveAssetFileFromRoots(assetRoots, baseTexture),
            selectedOptionAssets: selectedOptionAssets.slice(),
            outputIcon: iconRel,
            outputIconFile: iconFile
          };
          jobs.push(jobPayload);
          jobsByOutputPath.set(outputKey, jobPayload);
        }
      };

      if (sharedRoleGroup) {
        const placeholders = Object.assign({}, commonPlaceholders, { role: sharedIconRole });
        const filename = formatTemplate(config.filenameTemplate, placeholders);
        const iconRel = composeIconRelativePath(config.iconRelDir, filename);
        const iconFile = resolveCommonAssetFile(commonRoot, iconRel);
        roles.forEach((role) => {
          iconsByRole[role] = iconRel;
        });

        if (Object.keys(attachments).length) {
          sharedOverrides.push({
            Icon: iconRel,
            Attachments: Object.assign({}, attachments)
          });
        } else if (!sharedIconDefault) {
          sharedIconDefault = iconRel;
        }
        addRenderJob(sharedIconRole, iconRel, iconFile);
      } else {
        roles.forEach((role) => {
          const placeholders = Object.assign({}, commonPlaceholders, { role });
          const filename = formatTemplate(config.filenameTemplate, placeholders);
          const iconRel = composeIconRelativePath(config.iconRelDir, filename);
          const iconFile = resolveCommonAssetFile(commonRoot, iconRel);
          iconsByRole[role] = iconRel;

          if (Object.keys(attachments).length) {
            roleOverrides[role].push({
              Icon: iconRel,
              Attachments: Object.assign({}, attachments)
            });
          }
          addRenderJob(role, iconRel, iconFile);
        });
      }

      manifestCombos.push({
        index: comboIndex,
        comboSlug,
        setValues,
        attachments,
        iconsByRole
      });
    });

    return {
      roleOverrides,
      iconOverrideGroups:
        sharedRoleGroup && (sharedOverrides.length || sharedIconDefault)
          ? [
              Object.assign(
                { Roles: roles.slice(), Overrides: sharedOverrides },
                sharedIconDefault ? { IconDefault: sharedIconDefault } : {}
              )
            ]
          : [],
      manifest: {
        schema: "tamework.spawner-icon-manifest.v1",
        generatedAtUtc: new Date().toISOString(),
        modRoot,
        modelPath,
        roles,
        iconOverrideMode: sharedRoleGroup ? "group" : "byRole",
        randomAttachmentSets: setDefs.map((def) => ({
          set: def.name,
          options: def.options.slice(),
          includesEmptyState: !!def.includesEmpty
        })),
        comboCount: manifestCombos.length,
        combos: manifestCombos
      },
      jobsPayload: {
        schema: EXPECTED_SCHEMA,
        generatedAtUtc: new Date().toISOString(),
        renderer: "blockbench",
        assetRoot: modRoot,
        modelSource: modelPath,
        defaults: {
          iconSize: config.iconSize,
          camera: {
            scale: config.cameraScale,
            rotation: config.cameraRotation,
            translation: config.cameraTranslation,
            autoFrame: config.cameraAutoFrame === true,
            autoFramePadding: Math.max(0, Math.floor(asNumber(config.cameraAutoFramePadding, 4))),
            autoFrameMaxAttempts: Math.max(1, Math.floor(asNumber(config.cameraAutoFrameMaxAttempts, 6)))
          }
        },
        model: {
          baseModel,
          baseTexture,
          baseModelFile: resolveAssetFileFromRoots(assetRoots, baseModel),
          baseTextureFile: resolveAssetFileFromRoots(assetRoots, baseTexture)
        },
        jobCount: jobs.length,
        jobs
      }
    };
  }

  function requireHytaleCodec() {
    const codec = Codecs && Codecs.blockymodel;
    if (!codec) {
      throw new Error(
        "Codecs.blockymodel is not available. Install/enable the Hytale Models Blockbench plugin first."
      );
    }
    return codec;
  }

  async function loadBaseModel(codec, modelPath, texturePath) {
    if (!fileExists(modelPath)) {
      throw new Error(`Base model file not found: ${modelPath}`);
    }
    const beforeProjects = getOpenModelProjects();
    const previousProject = getActiveModelProject();
    const modelJson = readJsonFromDisk(modelPath);
    codec.load(
      modelJson,
      {
        path: modelPath,
        name: getPathModule().basename(modelPath)
      },
      {
        import_to_current_project: false
      }
    );
    await waitFrame();
    if (texturePath) {
      const texture = setDefaultTexture(texturePath);
      if (texture && texture.uuid) {
        const aliasKeys = collectTextureAliasKeys(texture);
        const detectedKey = normalizeTextureKey(detectPrimaryTextureKey(texturePath));
        if (detectedKey) {
          aliasKeys.add(detectedKey);
        }
        remapFaceTextureKeys(Array.from(aliasKeys), texture.uuid);
      }
      ensureFacesTextured(texture);
      await waitFrame();
    }
    return getNewModelProject(beforeProjects, previousProject);
  }

  function buildAttachmentCollection(name, content, modelPath, texturePath) {
    if (typeof Collection === "undefined" || !content || !Array.isArray(content.new_groups)) {
      return null;
    }
    const newGroups = content.new_groups;
    if (!newGroups.length) {
      return null;
    }
    const groupUuids = new Set(
      newGroups
        .map((group) => (group && group.uuid ? String(group.uuid) : null))
        .filter((entry) => !!entry)
    );
    const rootGroups = newGroups.filter((group) => {
      if (!group) {
        return false;
      }
      const parentRef = group.parent;
      const parentUuid =
        typeof parentRef === "string"
          ? parentRef
          : parentRef && typeof parentRef === "object" && parentRef.uuid
          ? String(parentRef.uuid)
          : null;
      return !parentUuid || !groupUuids.has(parentUuid);
    });
    const resolvedRootGroups = rootGroups.length ? rootGroups : [newGroups[0]];
    const collection = new Collection({
      name,
      children: resolvedRootGroups.map((group) => group.uuid),
      export_codec: "blockymodel",
      visibility: true
    }).add();
    collection.export_path = modelPath;

    let texture = null;
    if (texturePath) {
      texture = loadTextureFromPath(texturePath);
    }
    if (!texture && Array.isArray(content.new_textures) && content.new_textures.length) {
      texture = content.new_textures[0];
    }
    const uvSize = updateTextureUvSize(texture, texturePath || (texture && texture.path ? texture.path : ""));
    if (texture && texture.uuid) {
      // Hytale Blockbench codec resolves attachment faces through collection.texture.
      // Keeping this assigned is required for attachment rendering.
      collection.texture = texture.uuid;
      if (typeof Canvas !== "undefined" && typeof Canvas.updateAllFaces === "function") {
        Canvas.updateAllFaces();
      }
    }
    const remapResult = remapAttachmentElementTextures(content, texture, resolvedRootGroups);
    // Preserve parsed face routing while keeping collection.texture bound. The
    // Hytale attachment pipeline resolves collection faces through this value.
    return {
      collectionName: name,
      texturePath: texturePath || null,
      textureUuid: texture && texture.uuid ? String(texture.uuid) : null,
      textureSize: uvSize
        || (texture
          ? {
              width: Number.isFinite(texture.width) ? Number(texture.width) : null,
              height: Number.isFinite(texture.height) ? Number(texture.height) : null
            }
          : null),
      remapResult
    };
  }

  function isLikelyAttachmentModelPath(modelPath) {
    if (!modelPath) {
      return false;
    }
    const path = getPathModule();
    const normalized = String(modelPath).toLowerCase().replace(/\//g, "\\");
    const attachmentMarker = `\\attachments\\`;
    if (normalized.includes(attachmentMarker)) {
      return true;
    }
    const baseName = path.basename(modelPath).toLowerCase();
    return baseName.includes("attachment");
  }

  function hasBaseOverrideHint(asset, modelPath) {
    const path = getPathModule();
    const baseName = modelPath ? path.basename(modelPath).toLowerCase() : "";
    const setName = asset && typeof asset.set === "string" ? asset.set.toLowerCase() : "";
    const optionName = asset && typeof asset.option === "string" ? asset.option.toLowerCase() : "";
    const combined = `${setName} ${optionName} ${baseName}`;
    return (
      combined.includes("base") ||
      combined.includes("basecolor") ||
      combined.includes("base_color")
    );
  }

  function selectEffectiveBase(job, baseModelPath, baseTexturePath, jobsDir) {
    const assets = Array.isArray(job.selectedOptionAssets) ? job.selectedOptionAssets : [];
    const normalizedBase = normalizeForCompare(baseModelPath);
    let fallbackCandidate = null;
    for (let i = 0; i < assets.length; i += 1) {
      const asset = assets[i];
      const modelPath = normalizePath(asset && asset.modelFile, jobsDir);
      if (!modelPath) {
        continue;
      }
      if (normalizeForCompare(modelPath) === normalizedBase) {
        continue;
      }
      const baseHint = hasBaseOverrideHint(asset, modelPath);
      if (isLikelyAttachmentModelPath(modelPath) && !baseHint) {
        continue;
      }
      const texturePath = normalizePath(asset && asset.textureFile, jobsDir) || baseTexturePath;
      const candidate = {
        modelPath,
        texturePath,
        consumedAssetIndex: i
      };
      if (baseHint) {
        return candidate;
      }
      if (!fallbackCandidate) {
        fallbackCandidate = candidate;
      }
    }
    if (fallbackCandidate) {
      return fallbackCandidate;
    }
    return {
      modelPath: baseModelPath,
      texturePath: baseTexturePath,
      consumedAssetIndex: -1
    };
  }

  async function applyAttachments(
    codec,
    job,
    jobsDir,
    baseModelPath,
    baseTexturePath,
    consumedAssetIndex
  ) {
    const assets = Array.isArray(job.selectedOptionAssets) ? job.selectedOptionAssets : [];
    const sameModelTextureLayers = [];
    const skippedTransparentLayers = [];
    const attachmentTextureRemaps = [];
    const jobDebug = {
      id: typeof job.id === "string" ? job.id : job.comboSlug || "job",
      baseModelPath: baseModelPath || null,
      baseTexturePath: baseTexturePath || null,
      selectedOptionAssets: assets.map((asset) => ({
        set: asset && asset.set ? asset.set : null,
        option: asset && asset.option ? asset.option : null,
        modelFile: asset && asset.modelFile ? normalizePath(asset.modelFile, jobsDir) : null,
        textureFile: asset && asset.textureFile ? normalizePath(asset.textureFile, jobsDir) : null
      })),
      textureCatalogBeforeAttachments: getTextureCatalog(),
      faceTextureUsageBeforeAttachments: summarizeFaceTextureUsage(null, 24)
    };
    for (const asset of assets) {
      if (!asset || typeof asset !== "object") {
        continue;
      }
      if (consumedAssetIndex >= 0 && assets[consumedAssetIndex] === asset) {
        continue;
      }
      const modelPath = normalizePath(asset.modelFile, jobsDir);
      if (!modelPath) {
        continue;
      }
      const texturePath = normalizePath(asset.textureFile, jobsDir);
      if (samePath(modelPath, baseModelPath)) {
        if (texturePath && fileExists(texturePath)) {
          sameModelTextureLayers.push({
            asset,
            texturePath
          });
        }
        continue;
      }
      if (!fileExists(modelPath)) {
        throw new Error(`Attachment model not found: ${modelPath}`);
      }
      const attachmentJson = readJsonFromDisk(modelPath);
      const setName = typeof asset.set === "string" ? asset.set : "set";
      const optionName =
        typeof asset.option === "string"
          ? asset.option
          : getPathModule().basename(modelPath, getPathModule().extname(modelPath));
      const attachmentName = `${setName}_${optionName}`
        .replace(/[^A-Za-z0-9_]/g, "_")
        .slice(0, 80);
      const parseResult = codec.parse(attachmentJson, modelPath, { attachment: attachmentName });
      const remapInfo = buildAttachmentCollection(attachmentName, parseResult, modelPath, texturePath);
      if (remapInfo) {
        attachmentTextureRemaps.push(
          Object.assign(
            {
              set: setName,
              option: optionName,
              modelPath,
              texturePath: texturePath || null
            },
            remapInfo
          )
        );
      }
      await waitFrame();
    }

    const visibleSameModelTextureLayers = [];
    for (const layer of sameModelTextureLayers) {
      if (!layer || !layer.texturePath) {
        continue;
      }
      const hasPixels = await textureHasVisiblePixels(layer.texturePath);
      if (!hasPixels) {
        skippedTransparentLayers.push({
          set: layer.asset ? layer.asset.set : null,
          option: layer.asset ? layer.asset.option : null,
          texturePath: layer.texturePath
        });
        continue;
      }
      visibleSameModelTextureLayers.push(layer);
    }
    jobDebug.textureCatalogAfterAttachments = getTextureCatalog();
    jobDebug.faceTextureUsageAfterAttachments = summarizeFaceTextureUsage(null, 24);

    const layeredBaseTextures = [];
    const preferredBaseLayer = visibleSameModelTextureLayers.find((entry) => {
      if (!entry || !entry.texturePath) {
        return false;
      }
      const fileName = getPathModule().basename(entry.texturePath).toLowerCase();
      return hasBaseOverrideHint(entry.asset, baseModelPath) && !fileName.includes("empty");
    });

    const existingBaseTexturePath =
      baseTexturePath && fileExists(baseTexturePath) ? baseTexturePath : null;
    const inferredProjectTexturePath = resolveProjectTexturePath(jobsDir, "project_default");
    const baseMaskTexturePath = existingBaseTexturePath || inferredProjectTexturePath;
    let foundationTexturePath = null;

    if (preferredBaseLayer && baseMaskTexturePath) {
      foundationTexturePath = baseMaskTexturePath;
      layeredBaseTextures.push({
        texturePath: baseMaskTexturePath,
        mode: "source-over"
      });
      layeredBaseTextures.push({
        texturePath: preferredBaseLayer.texturePath,
        mode: "source-atop"
      });
    } else if (preferredBaseLayer) {
      foundationTexturePath = preferredBaseLayer.texturePath;
      layeredBaseTextures.push({
        texturePath: preferredBaseLayer.texturePath,
        mode: "source-over"
      });
    } else if (baseMaskTexturePath) {
      foundationTexturePath = baseMaskTexturePath;
      layeredBaseTextures.push({
        texturePath: baseMaskTexturePath,
        mode: "source-over"
      });
    }
    for (const layer of visibleSameModelTextureLayers) {
      if (!layer || !layer.texturePath) {
        continue;
      }
      if (preferredBaseLayer && samePath(layer.texturePath, preferredBaseLayer.texturePath)) {
        continue;
      }
      layeredBaseTextures.push({
        texturePath: layer.texturePath,
        mode: "source-over"
      });
    }
    if (!layeredBaseTextures.length) {
      return null;
    }
    const deduped = [];
    const seen = new Set();
    for (const layer of layeredBaseTextures) {
      const texturePath = layer && typeof layer.texturePath === "string" ? layer.texturePath : "";
      const mode = layer && typeof layer.mode === "string" ? layer.mode : "source-over";
      const key = `${normalizeForCompare(texturePath)}@${mode}`;
      if (!key || seen.has(key)) {
        continue;
      }
      seen.add(key);
      deduped.push({
        texturePath,
        mode
      });
    }
    const composedTexturePath = await composeTexturesToPath(deduped, jobsDir);
    jobDebug.sameModelTextureLayers = sameModelTextureLayers.map((entry) => ({
      set: entry && entry.asset ? entry.asset.set : null,
      option: entry && entry.asset ? entry.asset.option : null,
      texturePath: entry ? entry.texturePath : null
    }));
    jobDebug.visibleSameModelTextureLayers = visibleSameModelTextureLayers.map((entry) => ({
      set: entry && entry.asset ? entry.asset.set : null,
      option: entry && entry.asset ? entry.asset.option : null,
      texturePath: entry ? entry.texturePath : null
    }));
    jobDebug.skippedTransparentLayers = skippedTransparentLayers;
    jobDebug.attachmentTextureRemaps = attachmentTextureRemaps;
    jobDebug.preferredBaseLayer = preferredBaseLayer
      ? {
          set: preferredBaseLayer.asset ? preferredBaseLayer.asset.set : null,
          option: preferredBaseLayer.asset ? preferredBaseLayer.asset.option : null,
          texturePath: preferredBaseLayer.texturePath
        }
      : null;
    jobDebug.foundationTexturePath = foundationTexturePath || null;
    jobDebug.existingBaseTexturePath = existingBaseTexturePath;
    jobDebug.inferredProjectTexturePath = inferredProjectTexturePath;
    jobDebug.compositeLayers = deduped.slice();
    jobDebug.compositedTexturePath = composedTexturePath || null;
    runDebugRows.push(jobDebug);
    return composedTexturePath;
  }

  function choosePreview() {
    if (typeof Preview === "undefined") {
      throw new Error("Preview API is not available.");
    }
    const selected = Preview.selected || (Array.isArray(Preview.all) ? Preview.all[0] : null);
    if (selected && selected.canvas && selected.canvas.width > 0 && selected.canvas.height > 0) {
      return selected;
    }
    if (typeof MediaPreview !== "undefined") {
      return MediaPreview;
    }
    return selected;
  }

  function applyCamera(preview, defaults, job) {
    const cameraDefaults = defaults && defaults.camera && typeof defaults.camera === "object"
      ? defaults.camera
      : {};
    const scale = asNumber(
      (job && job.camera && job.camera.scale) || cameraDefaults.scale,
      1.0
    );
    let desiredDistance = null;
    let focusSize = null;

    if (
      typeof DefaultCameraPresets !== "undefined" &&
      Array.isArray(DefaultCameraPresets) &&
      DefaultCameraPresets.length > 0 &&
      typeof preview.loadAnglePreset === "function"
    ) {
      preview.loadAnglePreset(DefaultCameraPresets[0]);
    }
    if (typeof preview.setFOV === "function") {
      preview.setFOV(30);
    }

    if (
      preview.controls &&
      preview.controls.target &&
      typeof preview.controls.target.fromArray === "function" &&
      typeof getSelectionCenter === "function"
    ) {
      try {
        const center = getSelectionCenter(true);
        if (Array.isArray(center) && center.length >= 3) {
          preview.controls.target.fromArray(center);
          if (
            typeof scene !== "undefined" &&
            scene &&
            scene.position &&
            typeof preview.controls.target.add === "function"
          ) {
            preview.controls.target.add(scene.position);
          }
        }
      } catch (_error) {
        // Keep target if we cannot resolve selection center.
      }
    }

    if (
      preview.camera &&
      preview.camera.position &&
      typeof preview.camera.position.multiplyScalar === "function"
    ) {
      const modelSize =
        typeof Canvas !== "undefined" && typeof Canvas.getModelSize === "function"
          ? Canvas.getModelSize()
          : [2, 2, 2];
      focusSize = Math.max(
        asNumber(modelSize && modelSize[0], 0),
        asNumber(modelSize && modelSize[1], 0) * 2,
        asNumber(modelSize && modelSize[2], 0),
        1
      );
      const currentDistance =
        typeof preview.camera.position.length === "function"
          ? preview.camera.position.length()
          : 1;
      const safeDistance = Math.max(currentDistance, 0.001);
      desiredDistance = (focusSize * 1.2) / Math.max(scale, 0.001);
      preview.camera.position.multiplyScalar(desiredDistance / safeDistance);
    }

    // Tighten depth range to reduce precision artifacts (z-fighting) in Blockbench preview.
    if (
      preview.camera &&
      typeof preview.camera === "object" &&
      Number.isFinite(desiredDistance) &&
      Number.isFinite(focusSize)
    ) {
      const modelRadius = Math.max(0.5, focusSize * 0.9);
      const near = Math.max(0.02, desiredDistance - modelRadius * 1.6);
      const far = Math.max(near + 4, desiredDistance + modelRadius * 2.2);
      preview.camera.near = near;
      preview.camera.far = far;
    }

    if (preview.controls && typeof preview.controls.update === "function") {
      preview.controls.update();
    }
    if (preview.camera && typeof preview.camera.updateProjectionMatrix === "function") {
      preview.camera.updateProjectionMatrix();
    }
  }

  function scalePreviewCamera(preview, factor) {
    const scale = Math.max(0.01, asNumber(factor, 1));
    if (
      preview &&
      preview.camera &&
      preview.camera.position &&
      typeof preview.camera.position.multiplyScalar === "function"
    ) {
      preview.camera.position.multiplyScalar(scale);
    }
    if (preview && preview.controls && typeof preview.controls.update === "function") {
      preview.controls.update();
    }
    if (preview && preview.camera && typeof preview.camera.updateProjectionMatrix === "function") {
      preview.camera.updateProjectionMatrix();
    }
  }

  function captureScreenshot(preview, iconSize) {
    return new Promise((resolve, reject) => {
      try {
        Screencam.screenshotPreview(
          preview,
          {
            crop: false,
            width: iconSize,
            height: iconSize
          },
          (imageDataUrl) => {
            if (typeof imageDataUrl !== "string" || !imageDataUrl.startsWith("data:image/")) {
              reject(new Error("Screenshot callback did not return an image data URL."));
              return;
            }
            resolve(imageDataUrl);
          }
        );
      } catch (error) {
        reject(error);
      }
    });
  }

  function loadImage(dataUrl) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error("Failed to decode screenshot image."));
      image.src = dataUrl;
    });
  }

  function filePathToDataUrl(filePath) {
    const fs = getFsModule();
    const ext = getPathModule().extname(filePath).toLowerCase();
    const mime = ext === ".png" ? "image/png" : "application/octet-stream";
    const bytes = fs.readFileSync(filePath);
    return `data:${mime};base64,${getBufferCtor().from(bytes).toString("base64")}`;
  }

  async function textureHasVisiblePixels(texturePath) {
    const key = normalizeForCompare(texturePath);
    if (!key) {
      return false;
    }
    if (textureVisibilityCache.has(key)) {
      return textureVisibilityCache.get(key);
    }
    if (!fileExists(texturePath)) {
      textureVisibilityCache.set(key, false);
      return false;
    }
    try {
      const dataUrl = filePathToDataUrl(texturePath);
      const image = await loadImage(dataUrl);
      const width = Math.max(1, image.width || 1);
      const height = Math.max(1, image.height || 1);
      const canvas = document.createElement("canvas");
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext("2d");
      ctx.clearRect(0, 0, width, height);
      ctx.drawImage(image, 0, 0, width, height);
      const pixelData = ctx.getImageData(0, 0, width, height).data;
      for (let i = 3; i < pixelData.length; i += 4) {
        if (pixelData[i] !== 0) {
          textureVisibilityCache.set(key, true);
          return true;
        }
      }
      textureVisibilityCache.set(key, false);
      return false;
    } catch (_error) {
      // Keep unknown textures instead of accidentally stripping valid layers.
      textureVisibilityCache.set(key, true);
      return true;
    }
  }

  function hashString(input) {
    const raw = String(input || "");
    let hash = 2166136261;
    for (let i = 0; i < raw.length; i += 1) {
      hash ^= raw.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  }

  async function composeTexturesToPath(textureLayers, jobsDir) {
    const normalized = [];
    for (const entry of textureLayers || []) {
      let texturePath = null;
      let mode = "source-over";
      if (typeof entry === "string") {
        texturePath = entry;
      } else if (entry && typeof entry === "object") {
        texturePath = typeof entry.texturePath === "string" ? entry.texturePath : null;
        mode = typeof entry.mode === "string" && entry.mode.trim().length ? entry.mode.trim() : "source-over";
      }
      if (!texturePath) {
        continue;
      }
      const trimmed = texturePath.trim();
      if (!trimmed || !fileExists(trimmed)) {
        continue;
      }
      normalized.push({
        texturePath: trimmed,
        mode
      });
    }
    if (!normalized.length) {
      return null;
    }
    if (normalized.length === 1 && normalized[0].mode === "source-over") {
      return normalized[0].texturePath;
    }
    const cacheKey = normalized
      .map((entry) => `${normalizeForCompare(entry.texturePath)}@${entry.mode}`)
      .join("|");
    if (compositedTextureCache.has(cacheKey)) {
      return compositedTextureCache.get(cacheKey);
    }
    const images = [];
    for (const layer of normalized) {
      const dataUrl = filePathToDataUrl(layer.texturePath);
      const image = await loadImage(dataUrl);
      images.push({
        image,
        mode: layer.mode
      });
    }
    const width = Math.max(1, images[0].image.width || 1);
    const height = Math.max(1, images[0].image.height || 1);
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, width, height);
    for (const layer of images) {
      ctx.globalCompositeOperation = layer.mode || "source-over";
      ctx.drawImage(layer.image, 0, 0, width, height);
    }
    ctx.globalCompositeOperation = "source-over";
    const dataUrl = canvas.toDataURL("image/png");
    const base64 = dataUrl.split(",")[1];
    if (!base64) {
      throw new Error("Failed to compose layered texture.");
    }
    const outDir = getPathModule().join(jobsDir, ".tmp", "bb_icon_texture_cache");
    const outPath = getPathModule().join(outDir, `${hashString(cacheKey)}.png`);
    ensureDirectory(outPath);
    getFsModule().writeFileSync(outPath, getBufferCtor().from(base64, "base64"));
    compositedTextureCache.set(cacheKey, outPath);
    return outPath;
  }

  async function applyScreenTranslation(dataUrl, iconSize, translation) {
    const tx = asNumber(translation[0], 0);
    const ty = asNumber(translation[1], 0);
    if (tx === 0 && ty === 0) {
      return dataUrl;
    }
    const image = await loadImage(dataUrl);
    const canvas = document.createElement("canvas");
    canvas.width = iconSize;
    canvas.height = iconSize;
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, iconSize, iconSize);
    ctx.drawImage(image, tx, ty, iconSize, iconSize);
    return canvas.toDataURL("image/png");
  }

  async function analyzeImageAlphaBounds(dataUrl, iconSize) {
    const image = await loadImage(dataUrl);
    const canvas = document.createElement("canvas");
    canvas.width = iconSize;
    canvas.height = iconSize;
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, iconSize, iconSize);
    ctx.drawImage(image, 0, 0, iconSize, iconSize);
    const pixelData = ctx.getImageData(0, 0, iconSize, iconSize).data;
    let minX = iconSize;
    let minY = iconSize;
    let maxX = -1;
    let maxY = -1;
    let count = 0;
    for (let y = 0; y < iconSize; y += 1) {
      for (let x = 0; x < iconSize; x += 1) {
        const alpha = pixelData[(y * iconSize + x) * 4 + 3];
        if (alpha > 8) {
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
          count += 1;
        }
      }
    }
    if (count === 0) {
      return null;
    }
    return {
      minX,
      minY,
      maxX,
      maxY,
      width: maxX - minX + 1,
      height: maxY - minY + 1,
      count
    };
  }

  function boundsTouchPadding(bounds, iconSize, padding) {
    if (!bounds) {
      return false;
    }
    const safePadding = Math.max(0, Math.floor(asNumber(padding, 0)));
    return (
      bounds.minX < safePadding ||
      bounds.minY < safePadding ||
      bounds.maxX >= iconSize - safePadding ||
      bounds.maxY >= iconSize - safePadding
    );
  }

  async function centerImageByAlphaBounds(dataUrl, iconSize, bounds, padding) {
    if (!bounds) {
      return dataUrl;
    }
    const safePadding = Math.max(0, Math.floor(asNumber(padding, 0)));
    const padX = Math.min(safePadding, Math.max(0, Math.floor((iconSize - bounds.width) / 2)));
    const padY = Math.min(safePadding, Math.max(0, Math.floor((iconSize - bounds.height) / 2)));
    const centerX = (bounds.minX + bounds.maxX) / 2;
    const centerY = (bounds.minY + bounds.maxY) / 2;
    const desiredDx = Math.round(iconSize / 2 - centerX);
    const desiredDy = Math.round(iconSize / 2 - centerY);
    const minDx = padX - bounds.minX;
    const maxDx = iconSize - 1 - padX - bounds.maxX;
    const minDy = padY - bounds.minY;
    const maxDy = iconSize - 1 - padY - bounds.maxY;
    const dx = Math.max(minDx, Math.min(maxDx, desiredDx));
    const dy = Math.max(minDy, Math.min(maxDy, desiredDy));
    return applyScreenTranslation(dataUrl, iconSize, [dx, dy]);
  }

  function resolveAutoFrameSettings(payloadDefaults, job) {
    const cameraDefaults = payloadDefaults && payloadDefaults.camera && typeof payloadDefaults.camera === "object"
      ? payloadDefaults.camera
      : {};
    const jobCamera = job && job.camera && typeof job.camera === "object" ? job.camera : {};
    const rawEnabled =
      Object.prototype.hasOwnProperty.call(jobCamera, "autoFrame")
        ? jobCamera.autoFrame
        : cameraDefaults.autoFrame;
    const enabled = rawEnabled === true || rawEnabled === "true";
    const rawPadding = Object.prototype.hasOwnProperty.call(jobCamera, "autoFramePadding")
      ? jobCamera.autoFramePadding
      : cameraDefaults.autoFramePadding;
    const rawMaxAttempts = Object.prototype.hasOwnProperty.call(jobCamera, "autoFrameMaxAttempts")
      ? jobCamera.autoFrameMaxAttempts
      : cameraDefaults.autoFrameMaxAttempts;
    const padding = Math.max(
      0,
      Math.floor(asNumber(rawPadding, 4))
    );
    const maxAttempts = Math.max(
      1,
      Math.floor(asNumber(rawMaxAttempts, 6))
    );
    return {
      enabled,
      padding,
      maxAttempts
    };
  }

  async function captureAutoFramedScreenshot(preview, iconSize, settings) {
    let imageDataUrl = await captureScreenshot(preview, iconSize);
    if (!settings.enabled) {
      return imageDataUrl;
    }
    let bounds = await analyzeImageAlphaBounds(imageDataUrl, iconSize);
    if (!bounds) {
      return imageDataUrl;
    }
    for (let attempt = 1; attempt < settings.maxAttempts; attempt += 1) {
      if (!boundsTouchPadding(bounds, iconSize, settings.padding)) {
        break;
      }
      scalePreviewCamera(preview, 1.15);
      await waitFrame();
      imageDataUrl = await captureScreenshot(preview, iconSize);
      bounds = await analyzeImageAlphaBounds(imageDataUrl, iconSize);
      if (!bounds) {
        break;
      }
    }
    if (bounds && !boundsTouchPadding(bounds, iconSize, 1)) {
      imageDataUrl = await centerImageByAlphaBounds(imageDataUrl, iconSize, bounds, settings.padding);
    }
    return imageDataUrl;
  }

  function buildSceneDiagnostics(preview) {
    const elementCount =
      typeof Outliner !== "undefined" && Array.isArray(Outliner.elements)
        ? Outliner.elements.length
        : -1;
    const cubeCount =
      typeof Cube !== "undefined" && Array.isArray(Cube.all)
        ? Cube.all.length
        : -1;
    const canvasWidth = preview && preview.canvas ? preview.canvas.width : -1;
    const canvasHeight = preview && preview.canvas ? preview.canvas.height : -1;
    return `elements=${elementCount}, cubes=${cubeCount}, canvas=${canvasWidth}x${canvasHeight}`;
  }

  async function assertImageNotFullyTransparent(dataUrl, iconSize, diagnostics) {
    const image = await loadImage(dataUrl);
    const canvas = document.createElement("canvas");
    canvas.width = iconSize;
    canvas.height = iconSize;
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, iconSize, iconSize);
    ctx.drawImage(image, 0, 0, iconSize, iconSize);
    const pixelData = ctx.getImageData(0, 0, iconSize, iconSize).data;
    for (let i = 3; i < pixelData.length; i += 4) {
      if (pixelData[i] !== 0) {
        return;
      }
    }
    throw new Error(
      "Rendered image is fully transparent. "
      + "Model may be out of frame or have no active textured faces. "
      + diagnostics
    );
  }

  function writeImage(filePath, dataUrl) {
    return new Promise((resolve, reject) => {
      try {
        Blockbench.writeFile(
          filePath,
          {
            savetype: "image",
            content: dataUrl
          },
          (writtenPath) => {
            if (!writtenPath) {
              reject(new Error(`Blockbench.writeFile did not return an output path: ${filePath}`));
              return;
            }
            resolve(writtenPath);
          }
        );
      } catch (error) {
        reject(error);
      }
    });
  }

  async function renderSingleJobImageData(codec, payloadDefaults, job, jobsDir) {
    const baseModelPath = normalizePath(job.baseModelFile, jobsDir);
    const baseTexturePath = normalizePath(job.baseTextureFile, jobsDir);
    if (!baseModelPath) {
      throw new Error(`Job missing baseModelFile: ${JSON.stringify(job.id || job.comboSlug || job)}`);
    }

    const iconSize = Math.max(
      16,
      Math.floor(asNumber(job.iconSize, asNumber(payloadDefaults.iconSize, 128)))
    );
    const translation = asArrayNumbers(
      job.translation || (payloadDefaults.camera && payloadDefaults.camera.translation),
      2,
      [0, 0]
    );

    const effectiveBase = selectEffectiveBase(job, baseModelPath, baseTexturePath, jobsDir);
    const managedProject = await loadBaseModel(codec, effectiveBase.modelPath, effectiveBase.texturePath);
    try {
      const baseTextureRefPath = effectiveBase.texturePath || baseTexturePath;
      const primaryTextureKey = detectPrimaryTextureKey(baseTextureRefPath);
      const baseTextureBeforeOverride = getTextureByPath(baseTextureRefPath);
      const baseAliasKeys = collectTextureAliasKeys(baseTextureBeforeOverride);
      const normalizedPrimaryTextureKey = normalizeTextureKey(primaryTextureKey);
      if (normalizedPrimaryTextureKey) {
        baseAliasKeys.add(normalizedPrimaryTextureKey);
      }
      const baseTextureOverride = await applyAttachments(
        codec,
        job,
        jobsDir,
        effectiveBase.modelPath,
        effectiveBase.texturePath,
        effectiveBase.consumedAssetIndex
      );
      if (baseTextureOverride) {
        const texture = setDefaultTexture(baseTextureOverride);
        let remappedFaces = 0;
        if (texture && texture.uuid) {
          remappedFaces = remapFaceTextureKeys(Array.from(baseAliasKeys), texture.uuid);
        }
        if (remappedFaces === 0) {
          ensureFacesTextured(texture);
        }
        const debugRow = runDebugRows.length ? runDebugRows[runDebugRows.length - 1] : null;
        const jobId = typeof job.id === "string" ? job.id : job.comboSlug || "job";
        if (debugRow && debugRow.id === jobId) {
          debugRow.primaryTextureKey = primaryTextureKey;
          debugRow.baseAliasKeysBeforeOverride = Array.from(baseAliasKeys);
          debugRow.overrideTexturePath = baseTextureOverride;
          debugRow.overrideTextureUuid = texture && texture.uuid ? texture.uuid : null;
          debugRow.remappedFaces = remappedFaces;
          debugRow.textureCatalogAfterBaseOverride = getTextureCatalog();
          debugRow.faceTextureUsageAfterBaseOverride = summarizeFaceTextureUsage(null, 24);
        }
        await waitFrame();
      }
      const zeroUvCleanup = clearZeroAreaUvFaces();
      const debugRowAfterCleanup = runDebugRows.length ? runDebugRows[runDebugRows.length - 1] : null;
      const debugJobIdAfterCleanup = typeof job.id === "string" ? job.id : job.comboSlug || "job";
      if (debugRowAfterCleanup && debugRowAfterCleanup.id === debugJobIdAfterCleanup) {
        debugRowAfterCleanup.zeroUvCleanup = zeroUvCleanup;
        debugRowAfterCleanup.faceTextureUsageAfterZeroUvCleanup = summarizeFaceTextureUsage(null, 24);
      }
      await waitFrame();
      const preview = choosePreview();
      if (!preview) {
        throw new Error("Could not resolve an active Blockbench preview.");
      }
      if (typeof preview.resize === "function") {
        preview.resize(iconSize, iconSize);
      }
      applyCamera(preview, payloadDefaults, job);
      await waitFrame();

      const autoFrame = resolveAutoFrameSettings(payloadDefaults, job);
      let imageDataUrl = await captureAutoFramedScreenshot(preview, iconSize, autoFrame);
      imageDataUrl = await applyScreenTranslation(imageDataUrl, iconSize, translation);
      await assertImageNotFullyTransparent(imageDataUrl, iconSize, buildSceneDiagnostics(preview));
      return {
        imageDataUrl,
        iconSize
      };
    } finally {
      await closeManagedModelProject(managedProject);
    }
  }

  async function renderSingleJob(codec, payloadDefaults, job, jobsDir) {
    const outputPath = normalizePath(job.outputIconFile, jobsDir);
    if (!outputPath) {
      throw new Error(`Job missing outputIconFile: ${JSON.stringify(job.id || job.comboSlug || job)}`);
    }
    const rendered = await renderSingleJobImageData(codec, payloadDefaults, job, jobsDir);

    ensureDirectory(outputPath);
    await writeImage(outputPath, rendered.imageDataUrl);
    return outputPath;
  }

  async function renderFirstJobPreview(payload, jobsDir) {
    if (!payload || typeof payload !== "object" || !Array.isArray(payload.jobs)) {
      throw new Error("Invalid preview payload.");
    }
    if (!payload.jobs.length) {
      throw new Error("No jobs were generated for preview.");
    }
    const codec = requireHytaleCodec();
    const defaults = payload.defaults && typeof payload.defaults === "object" ? payload.defaults : {};
    const firstJob = payload.jobs[0];
    const label = typeof firstJob.id === "string" ? firstJob.id : "preview-job-1";
    runDebugRows.length = 0;
    textureVisibilityCache.clear();
    compositedTextureCache.clear();
    Blockbench.setProgress(0.5);
    try {
      const rendered = await withTimeout(
        renderSingleJobImageData(codec, defaults, firstJob, jobsDir),
        JOB_TIMEOUT_MS,
        label
      );
      return {
        imageDataUrl: rendered.imageDataUrl,
        iconSize: rendered.iconSize,
        jobId: label
      };
    } finally {
      Blockbench.setProgress();
    }
  }

  async function runBatchPayload(payload, jobsDir) {
    const fs = getFsModule();
    const path = getPathModule();
    const codec = requireHytaleCodec();
    const total = payload.jobs.length;
    const defaults = payload.defaults && typeof payload.defaults === "object" ? payload.defaults : {};
    const failures = [];
    runDebugRows.length = 0;
    textureVisibilityCache.clear();
    compositedTextureCache.clear();
    const startedAt = Date.now();
    const debugFilePath = path.join(jobsDir, ".tmp", "spawner_icon_debug_last_run.json");
    const variantDebugDir = buildVariantDebugSnapshotDir(jobsDir);
    let variantDebugCaptured = false;

    try {
      fs.rmSync(variantDebugDir, { recursive: true, force: true });
    } catch (_error) {
      // Best effort cleanup only.
    }

    const writeRunDebug = (extra) => {
      writeJson(debugFilePath, Object.assign(
        {
          schema: "tamework.spawner-icon-debug.v1",
          generatedAtUtc: new Date().toISOString(),
          status: "running",
          jobsDir,
          variantDebugDir,
          totalJobs: total,
          completedJobs: runDebugRows.length,
          failureCount: failures.length,
          failures,
          jobs: runDebugRows
        },
        extra || {}
      ));
    };

    writeRunDebug({ status: "running", currentJobIndex: 0, currentJobId: null });

    for (let i = 0; i < total; i += 1) {
      const job = payload.jobs[i];
      const label = typeof job.id === "string" ? job.id : `job-${i + 1}`;
      Blockbench.setProgress((i + 1) / total);
      writeRunDebug({ status: "running", currentJobIndex: i + 1, currentJobId: label });
      try {
        await withTimeout(
          renderSingleJob(codec, defaults, job, jobsDir),
          JOB_TIMEOUT_MS,
          label
        );
      } catch (error) {
        failures.push({
          index: i + 1,
          id: label,
          message: error && error.message ? error.message : String(error)
        });
        console.error(`[${PLUGIN_ID}] Failed ${label}`, error);
      }
      if (!variantDebugCaptured) {
        const jobId = typeof job.id === "string" ? job.id : null;
        const debugRow = jobId
          ? runDebugRows.find((entry) => entry && entry.id === jobId) || null
          : runDebugRows.length
          ? runDebugRows[runDebugRows.length - 1]
          : null;
        try {
          captureVariantDebugSnapshot(job, debugRow, jobsDir);
          variantDebugCaptured = true;
        } catch (snapshotError) {
          console.error(`[${PLUGIN_ID}] Failed to capture variant debug snapshot`, snapshotError);
        }
      }
      writeRunDebug({ status: "running", currentJobIndex: i + 1, currentJobId: label });
    }

    Blockbench.setProgress();
    const elapsedSec = ((Date.now() - startedAt) / 1000).toFixed(1);
    const successCount = total - failures.length;
    writeRunDebug({
      status: "completed",
      elapsedSec,
      successCount,
      failureCount: failures.length,
      currentJobIndex: total,
      currentJobId: null
    });
    return {
      total,
      successCount,
      failures,
      elapsedSec,
      debugFilePath,
      variantDebugDir
    };
  }

  function buildRunSummaryText(summary) {
    let message = `Completed ${summary.successCount}/${summary.total} render jobs in ${summary.elapsedSec}s.`;
    if (summary.failures.length) {
      const abbreviate = (raw) => {
        const singleLine = String(raw || "").replace(/\s+/g, " ").trim();
        if (singleLine.length <= 180) {
          return singleLine;
        }
        return `${singleLine.slice(0, 177)}...`;
      };
      const sampleLimit = 4;
      const sample = summary.failures
        .slice(0, sampleLimit)
        .map((entry) => `#${entry.index} ${entry.id}: ${abbreviate(entry.message)}`)
        .join("\n");
      message += `\n\nFailures: ${summary.failures.length}\n${sample}`;
      if (summary.failures.length > sampleLimit) {
        message += `\n...and ${summary.failures.length - sampleLimit} more`;
      }
    }
    if (summary.debugFilePath) {
      message += `\n\nDebug log:\n${summary.debugFilePath}`;
    }
    if (summary.variantDebugDir) {
      message += `\n\nVariant debug files:\n${summary.variantDebugDir}`;
    }
    return message;
  }

  async function runBatch(jobsFilePath) {
    const path = getPathModule();
    const jobsDir = path.dirname(jobsFilePath);
    const payload = parseJobsPayload(readJsonFromDisk(jobsFilePath));
    const summary = await runBatchPayload(payload, jobsDir);
    await showTextDialog("Spawner Icon Batch Renderer", buildRunSummaryText(summary), 980);
    return summary;
  }

  function getCurrentProjectPath() {
    if (typeof Project === "undefined" || !Project || typeof Project !== "object") {
      return "";
    }
    const candidates = [Project.export_path, Project.save_path, Project.path];
    for (const candidate of candidates) {
      if (typeof candidate === "string" && candidate.trim().length) {
        return candidate.trim();
      }
    }
    return "";
  }

  function getWizardDefaults() {
    const defaults = {
      modelPath: "",
      spawnerPath: "",
      rolesCsv: "",
      includeEmptySets: "",
      excludeSetsCsv: "",
      emptyValueToken: "none",
      iconRelDir: "Icons/ItemsGenerated/Generated",
      filenameTemplate: "",
      iconSize: 64,
      cameraScale: 1.0,
      cameraRotationX: 22.5,
      cameraRotationY: 45.0,
      cameraRotationZ: 22.5,
      cameraPositionX: 0,
      cameraPositionY: 0,
      cameraAutoFrame: false,
      cameraAutoFramePadding: 4,
      cameraAutoFrameMaxAttempts: 6,
      saveGeneratedJson: true,
      jobsOutPath: "",
      manifestOutPath: "",
      writeSpawnerOverrides: true,
      sharedRoleGroup: false,
      writeSpawnerInPlace: true,
      spawnerOutPath: "",
      iconDefaultOverride: ""
    };
    if (wizardLastValues && typeof wizardLastValues === "object") {
      Object.assign(defaults, wizardLastValues);
    }
    const legacyRotation = parseVectorText(String(defaults.cameraRotation || ""), 3, [22.5, 45.0, 22.5]);
    const legacyPosition = parseVectorText(String(defaults.cameraTranslation || ""), 2, [0, 0]);
    if (!Number.isFinite(Number(defaults.cameraRotationX))) defaults.cameraRotationX = legacyRotation[0];
    if (!Number.isFinite(Number(defaults.cameraRotationY))) defaults.cameraRotationY = legacyRotation[1];
    if (!Number.isFinite(Number(defaults.cameraRotationZ))) defaults.cameraRotationZ = legacyRotation[2];
    if (!Number.isFinite(Number(defaults.cameraPositionX))) defaults.cameraPositionX = legacyPosition[0];
    if (!Number.isFinite(Number(defaults.cameraPositionY))) defaults.cameraPositionY = legacyPosition[1];
    if (!Number.isFinite(Number(defaults.cameraAutoFramePadding))) defaults.cameraAutoFramePadding = 4;
    if (!Number.isFinite(Number(defaults.cameraAutoFrameMaxAttempts))) defaults.cameraAutoFrameMaxAttempts = 6;
    if (!defaults.modelPath) {
      const projectPath = getCurrentProjectPath();
      if (projectPath) {
        defaults.modelPath = projectPath;
      }
    }
    return defaults;
  }

  function ensureWizardDialogStyle() {
    if (typeof document === "undefined") {
      return;
    }
    let style = document.getElementById("tw_spawner_wizard_style");
    if (!style) {
      style = document.createElement("style");
      style.id = "tw_spawner_wizard_style";
      document.head.appendChild(style);
    }
    style.textContent = `
      #tw-spawner-wizard-layout {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 12px;
        max-height: 70vh;
        overflow-y: auto;
        padding: 2px 4px 2px 1px;
      }
      #tw-spawner-wizard-layout .tw-full {
        grid-column: 1 / span 2;
      }
      #tw-spawner-wizard-layout .tw-card {
        background: #1b212c;
        border: 1px solid #3a4458;
        border-radius: 8px;
        padding: 10px;
      }
      #tw-spawner-wizard-layout .tw-card-title {
        font-size: 11px;
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: #d7dfef;
        margin-bottom: 10px;
      }
      #tw-spawner-wizard-layout .tw-field {
        margin-bottom: 10px;
      }
      #tw-spawner-wizard-layout .tw-field:last-child {
        margin-bottom: 0;
      }
      #tw-spawner-wizard-layout .tw-field label {
        display: block;
        font-size: 12px;
        color: #c9d3e6;
        margin-bottom: 5px;
      }
      #tw-spawner-wizard-layout .tw-field input[type="text"],
      #tw-spawner-wizard-layout .tw-field input[type="number"] {
        width: 100%;
        box-sizing: border-box;
        min-height: 30px;
        padding: 6px 8px;
        border: 1px solid #4a5670;
        border-radius: 5px;
        background: #111722;
        color: #f1f5ff;
        font-size: 13px;
      }
      #tw-spawner-wizard-layout .tw-field input[type="text"]:focus,
      #tw-spawner-wizard-layout .tw-field input[type="number"]:focus {
        outline: none;
        border-color: #7f9cff;
        box-shadow: 0 0 0 1px rgba(127, 156, 255, 0.4);
      }
      #tw-spawner-wizard-layout .tw-field numeric-input {
        width: 100%;
      }
      #tw-spawner-wizard-layout .tw-path-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        gap: 6px;
        align-items: center;
      }
      #tw-spawner-wizard-layout .tw-path-row .tool {
        min-width: 86px;
        height: 30px;
        border: 1px solid #5b6f98;
        border-radius: 5px;
        background: linear-gradient(180deg, #2b3f63 0%, #22344f 100%);
        color: #eaf1ff;
        box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08), 0 1px 2px rgba(0, 0, 0, 0.35);
      }
      #tw-spawner-wizard-layout .tw-path-row .tool:hover {
        background: linear-gradient(180deg, #344d79 0%, #2b4162 100%);
        border-color: #7392c8;
      }
      #tw-spawner-wizard-layout .tw-path-row .tool:active {
        background: #223652;
      }
      #tw-spawner-wizard-layout .tw-path-row .tool:focus {
        outline: none;
        border-color: #86a9ec;
        box-shadow: 0 0 0 1px rgba(134, 169, 236, 0.45);
      }
      #tw-spawner-wizard-layout .tw-check-row {
        display: flex;
        align-items: center;
        gap: 8px;
        min-height: 30px;
      }
      #tw-spawner-wizard-layout .tw-check-row input[type="checkbox"] {
        display: block;
        width: 16px;
        height: 16px;
        margin: 0;
        position: relative;
        top: -1px;
      }
      #tw-spawner-wizard-layout .tw-check-row label {
        display: inline-flex;
        align-items: center;
        margin: 0;
        font-size: 12px;
        line-height: 1.1;
      }
      #tw-spawner-wizard-layout .tw-help {
        font-size: 11px;
        color: #9fb0cf;
        margin-top: 4px;
      }
      #tw-spawner-wizard-layout .tw-inline-pair {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 10px;
      }
      #tw-spawner-wizard-layout .tw-inline-triple {
        display: grid;
        grid-template-columns: 1fr 1fr 1fr;
        gap: 10px;
      }
      #tw-spawner-wizard-layout .tw-action-row {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 4px;
      }
      #tw-spawner-wizard-layout .tw-action-row .tool {
        min-width: 168px;
        height: 30px;
        border: 1px solid #5b6f98;
        border-radius: 5px;
        background: linear-gradient(180deg, #2b3f63 0%, #22344f 100%);
        color: #eaf1ff;
        box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08), 0 1px 2px rgba(0, 0, 0, 0.35);
        white-space: nowrap;
      }
      #tw-spawner-wizard-layout .tw-action-row .tool:hover {
        background: linear-gradient(180deg, #344d79 0%, #2b4162 100%);
        border-color: #7392c8;
      }
      #tw-spawner-wizard-layout .tw-action-row .tool:active {
        background: #223652;
      }
      #tw-spawner-wizard-layout .tw-action-row .tool:focus {
        outline: none;
        border-color: #86a9ec;
        box-shadow: 0 0 0 1px rgba(134, 169, 236, 0.45);
      }
      #tw-spawner-wizard-layout .tw-calc-summary {
        margin-top: 8px;
        max-height: 180px;
        overflow: auto;
        border: 1px solid #3a4458;
        border-radius: 6px;
        background: #121826;
        padding: 8px;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        word-break: break-word;
        line-height: 1.35;
        font-size: 12px;
        color: #dce6fb;
      }
      #tw-spawner-wizard-layout .tw-preview-panel {
        margin-top: 8px;
        min-height: 180px;
        border: 1px solid #3a4458;
        border-radius: 6px;
        background: #121826;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
      }
      #tw-spawner-wizard-layout .tw-preview-empty {
        text-align: center;
        color: #9fb0cf;
        font-size: 12px;
        padding: 12px;
      }
      #tw-spawner-wizard-layout .tw-preview-image {
        width: 100%;
        height: 100%;
        object-fit: contain;
        image-rendering: pixelated;
      }
      .tw-message-wrap {
        max-height: 70vh;
        overflow: auto;
        background: #1b212c;
        border: 1px solid #3a4458;
        border-radius: 8px;
        padding: 12px;
      }
      .tw-message-text {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        word-break: break-word;
        line-height: 1.45;
        font-size: 13px;
        color: #e8eefc;
      }
    `;
  }

  function showTextDialog(title, message, width) {
    return new Promise((resolve) => {
      const text = typeof message === "string" ? message : String(message || "");
      if (typeof Dialog === "undefined") {
        Blockbench.showMessageBox(
          {
            title,
            message: text
          },
          () => resolve()
        );
        return;
      }
      ensureWizardDialogStyle();
      const dialogId = `tw_spawner_msg_${Date.now()}_${Math.floor(Math.random() * 1e6)}`;
      let dialog = null;
      const finish = () => {
        try {
          if (dialog) {
            dialog.hide();
          }
        } catch (_error) {}
        try {
          if (dialog && typeof dialog.delete === "function") {
            dialog.delete();
          }
        } catch (_error) {}
        resolve();
      };
      dialog = new Dialog({
        id: dialogId,
        title: title || "Message",
        width: Math.max(720, Math.floor(asNumber(width, 960))),
        buttons: ["OK"],
        confirmIndex: 0,
        cancelIndex: 0,
        component: {
          data() {
            return {
              text
            };
          },
          template: `
            <div class="tw-message-wrap">
              <div class="tw-message-text">{{ text }}</div>
            </div>
          `
        },
        onConfirm() {
          finish();
        },
        onCancel() {
          finish();
        }
      });
      dialog.show();
    });
  }

  function deriveWizardPathsFromModel(modelPath) {
    const resolved = resolveUserPath(modelPath, getPathModule().resolve("."));
    if (!resolved) {
      return null;
    }
    if (!fileExists(resolved)) {
      return null;
    }
    try {
      const modRoot = inferModRootFromServerPath(resolved);
      const modelName = inferModelName(resolved);
      const path = getPathModule();
      return {
        modRoot,
        modelName,
        jobsOutPath: path.join(modRoot, ".tmp", `${modelName}_spawner_render_jobs.json`),
        manifestOutPath: path.join(modRoot, ".tmp", `${modelName}_spawner_icon_manifest.json`)
      };
    } catch (_error) {
      return null;
    }
  }

  function buildComboPreviewText(summary) {
    const lines = [];
    lines.push(`Model: ${summary.modelName}`);
    lines.push(`Roles (${summary.roles.length}): ${summary.roles.join(", ")}`);
    lines.push(`Total set categories: ${summary.totalSetCount}`);
    lines.push(`Active set categories: ${summary.activeSetCount}`);
    if (summary.excludedSets.length) {
      lines.push(`Excluded sets (${summary.excludedSets.length}): ${summary.excludedSets.join(", ")}`);
    }
    if (summary.includeEmptySets.length) {
      lines.push(
        `Include-empty sets (${summary.includeEmptySets.length}): ${summary.includeEmptySets.join(", ")}`
      );
    }
    if (summary.includeIgnoredBecauseExcluded.length) {
      lines.push(
        `Ignored include-empty entries (excluded): ${summary.includeIgnoredBecauseExcluded.join(", ")}`
      );
    }
    lines.push("");
    if (summary.activeSetDefs.length) {
      lines.push("Active set option counts:");
      summary.activeSetDefs.forEach((setDef) => {
        lines.push(
          `- ${setDef.name}: ${formatIntegerWithSeparators(setDef.options.length)}`
            + (setDef.includesEmpty ? " (includes empty)" : "")
        );
      });
    } else {
      lines.push("No active random attachment sets.");
    }
    lines.push("");
    lines.push(
      `Possible attachment combinations: ${formatIntegerWithSeparators(summary.comboCount)}`
    );
    lines.push(
      `Estimated rendered icon files: ${formatIntegerWithSeparators(summary.estimatedIconCount)}`
    );
    if (summary.roles.length > 1 && !summary.filenameUsesRoleToken) {
      lines.push("Note: filename template does not include {role}, so icons are shared across roles.");
    }
    if (!summary.templateContainsComboToken) {
      lines.push(
        "Note: filename template does not include combo placeholders, so output collisions may reduce files."
      );
    }
    return lines.join("\n");
  }

  function calculateWizardCombinationPreview(values) {
    const path = getPathModule();
    const appRoot = path.resolve(".");

    const modelPath = resolveUserPath(values.modelPath, appRoot);
    if (!modelPath) {
      throw new Error("Model JSON path is required.");
    }
    if (!fileExists(modelPath)) {
      throw new Error(`Model JSON not found: ${modelPath}`);
    }
    const modelJson = readJsonFromDisk(modelPath);
    if (!modelJson || typeof modelJson !== "object") {
      throw new Error(`Model JSON is invalid: ${modelPath}`);
    }

    const modRoot = inferModRootFromServerPath(modelPath);
    const modelName = inferModelName(modelPath);
    const spawnerPathRaw = typeof values.spawnerPath === "string" ? values.spawnerPath.trim() : "";
    const spawnerPath = spawnerPathRaw ? resolveUserPath(spawnerPathRaw, modRoot) : null;
    let spawnerJson = null;
    if (spawnerPath) {
      if (!fileExists(spawnerPath)) {
        throw new Error(`Spawner JSON not found: ${spawnerPath}`);
      }
      spawnerJson = readJsonFromDisk(spawnerPath);
      if (!spawnerJson || typeof spawnerJson !== "object") {
        throw new Error(`Spawner JSON is invalid: ${spawnerPath}`);
      }
    }

    const discoveredRoles = spawnerJson ? discoverRolesFromSpawner(spawnerJson) : [];
    const typedRoles = parseCsv(values.rolesCsv);
    const roles = typedRoles.length
      ? typedRoles
      : discoveredRoles.length
      ? discoveredRoles
      : [modelName];
    if (!roles.length) {
      throw new Error("At least one role is required.");
    }

    const setSelection = resolveSetSelectionConfig(
      modelJson,
      values.includeEmptySets,
      values.excludeSetsCsv
    );
    const comboCount = calculateComboCountFromSetDefinitions(setSelection.activeSetDefs);

    const filenameTemplate = String(values.filenameTemplate || "").trim() || `${modelName}_{combo_slug}.png`;
    const filenameUsesRoleToken = filenameTemplate.includes("{role}");
    const templateContainsComboToken =
      filenameTemplate.includes("{combo_slug}") || filenameTemplate.includes("{combo_index}");
    const sharedRoleGroup = values.sharedRoleGroup === true;
    const roleMultiplier = filenameUsesRoleToken && !sharedRoleGroup ? BigInt(roles.length) : 1n;
    const estimatedIconCount = comboCount * roleMultiplier;

    return buildComboPreviewText({
      modelName,
      roles,
      totalSetCount: setSelection.allSetDefs.length,
      activeSetCount: setSelection.activeSetDefs.length,
      excludedSets: setSelection.excludedSets,
      includeEmptySets: setSelection.includeEmptySets,
      includeIgnoredBecauseExcluded: setSelection.includeIgnoredBecauseExcluded,
      activeSetDefs: setSelection.activeSetDefs,
      comboCount,
      estimatedIconCount,
      filenameUsesRoleToken,
      templateContainsComboToken
    });
  }

  async function renderWizardFirstComboPreview(values) {
    const path = getPathModule();
    const appRoot = path.resolve(".");

    const modelPath = resolveUserPath(values.modelPath, appRoot);
    if (!modelPath) {
      throw new Error("Model JSON path is required.");
    }
    if (!fileExists(modelPath)) {
      throw new Error(`Model JSON not found: ${modelPath}`);
    }
    const modelJson = readJsonFromDisk(modelPath);
    if (!modelJson || typeof modelJson !== "object") {
      throw new Error(`Model JSON is invalid: ${modelPath}`);
    }

    const modRoot = inferModRootFromServerPath(modelPath);
    const commonRoot = path.join(modRoot, "Common");
    const modelName = inferModelName(modelPath);
    const spawnerPathRaw = typeof values.spawnerPath === "string" ? values.spawnerPath.trim() : "";
    const spawnerPath = spawnerPathRaw ? resolveUserPath(spawnerPathRaw, modRoot) : null;
    let spawnerJson = null;
    if (spawnerPath) {
      if (!fileExists(spawnerPath)) {
        throw new Error(`Spawner JSON not found: ${spawnerPath}`);
      }
      spawnerJson = readJsonFromDisk(spawnerPath);
      if (!spawnerJson || typeof spawnerJson !== "object") {
        throw new Error(`Spawner JSON is invalid: ${spawnerPath}`);
      }
    }

    const assetRoots = buildAssetSearchRoots(modRoot, [modelPath, spawnerPath].filter(Boolean));
    const discoveredRoles = spawnerJson ? discoverRolesFromSpawner(spawnerJson) : [];
    const typedRoles = parseCsv(values.rolesCsv);
    const roles = typedRoles.length
      ? typedRoles
      : discoveredRoles.length
      ? discoveredRoles
      : [modelName];
    if (!roles.length) {
      throw new Error("At least one role is required.");
    }

    const setSelection = resolveSetSelectionConfig(
      modelJson,
      values.includeEmptySets,
      values.excludeSetsCsv
    );

    const defaultFilenameTemplate = `${modelName}_{combo_slug}.png`;
    const iconRelDir = normalizeRelDir(
      values.iconRelDir,
      "Icons/ItemsGenerated/Generated"
    );
    const filenameTemplate = String(values.filenameTemplate || "").trim() || defaultFilenameTemplate;
    const iconSize = Math.max(16, Math.floor(asNumber(values.iconSize, 64)));
    const cameraScale = Math.max(0.01, asNumber(values.cameraScale, 1.0));
    const legacyRotation = parseVectorText(String(values.cameraRotation || ""), 3, [22.5, 45.0, 22.5]);
    const legacyTranslation = parseVectorText(String(values.cameraTranslation || ""), 2, [0, 0]);
    const cameraRotation = [
      asNumber(values.cameraRotationX, legacyRotation[0]),
      asNumber(values.cameraRotationY, legacyRotation[1]),
      asNumber(values.cameraRotationZ, legacyRotation[2])
    ];
    const cameraTranslation = [
      asNumber(values.cameraPositionX, legacyTranslation[0]),
      asNumber(values.cameraPositionY, legacyTranslation[1])
    ];
    const emptyValueToken = String(values.emptyValueToken || "none").trim() || "none";

    const generated = buildGeneratedPayload({
      modelJson,
      modelPath,
      modRoot,
      commonRoot,
      roles,
      assetRoots,
      includeEmptySets: setSelection.includeEmptySets,
      excludeSets: setSelection.excludedSets,
      emptyValueToken,
      iconRelDir,
      filenameTemplate,
      iconSize,
      cameraScale,
      cameraRotation,
      cameraTranslation,
      cameraAutoFrame: values.cameraAutoFrame === true,
      cameraAutoFramePadding: Math.max(0, Math.floor(asNumber(values.cameraAutoFramePadding, 4))),
      cameraAutoFrameMaxAttempts: Math.max(1, Math.floor(asNumber(values.cameraAutoFrameMaxAttempts, 6))),
      sharedRoleGroup: values.sharedRoleGroup === true,
      previewOnlyFirstCombo: true
    });
    const firstJob = generated.jobsPayload
      && Array.isArray(generated.jobsPayload.jobs)
      && generated.jobsPayload.jobs.length
      ? generated.jobsPayload.jobs[0]
      : null;
    if (!firstJob) {
      throw new Error("No preview job could be generated with current settings.");
    }
    const baseModelFile = firstJob.baseModelFile;
    if (!baseModelFile || !fileExists(baseModelFile)) {
      throw new Error(`Base model file not found for preview: ${baseModelFile || "(missing)"}`);
    }
    const preview = await renderFirstJobPreview(generated.jobsPayload, modRoot);
    return {
      imageDataUrl: preview.imageDataUrl,
      iconSize: preview.iconSize,
      jobId: preview.jobId,
      comboSlug: firstJob.comboSlug || null
    };
  }

  function showWizardConfigDialog(defaults) {
    return new Promise((resolve) => {
      if (typeof Dialog === "undefined") {
        resolve(null);
        return;
      }
      const dialogId = "tw_spawner_batch_wizard";
      let dialog = null;
      const finish = (value) => {
        try {
          if (dialog) {
            dialog.hide();
          }
        } catch (_error) {}
        try {
          if (dialog && typeof dialog.delete === "function") {
            dialog.delete();
          }
        } catch (_error) {}
        resolve(value);
      };
      ensureWizardDialogStyle();
      dialog = new Dialog({
        id: dialogId,
        title: "Spawner Icon Batch Wizard",
        width: 860,
        buttons: ["Run Batch", "Cancel"],
        confirmIndex: 0,
        cancelIndex: 1,
        component: {
          data() {
            return {
              values: Object.assign({}, defaults),
              comboSummary: "",
              previewImageDataUrl: "",
              previewStatus: "",
              previewBusy: false
            };
          },
          methods: {
            autoFillFromModelPath() {
              const derived = deriveWizardPathsFromModel(this.values.modelPath);
              if (!derived) {
                return;
              }
              if (!String(this.values.jobsOutPath || "").trim()) {
                this.values.jobsOutPath = derived.jobsOutPath;
              }
              if (!String(this.values.manifestOutPath || "").trim()) {
                this.values.manifestOutPath = derived.manifestOutPath;
              }
            },
            autoFillSpawnerOut() {
              const rawSpawner = String(this.values.spawnerPath || "").trim();
              if (!rawSpawner) {
                return;
              }
              if (String(this.values.spawnerOutPath || "").trim()) {
                return;
              }
              this.values.spawnerOutPath = replaceFileExt(rawSpawner, ".generated.json");
            },
            async browsePath(fieldKey) {
              const startPath =
                String(this.values[fieldKey] || "").trim() ||
                String(this.values.modelPath || "").trim() ||
                "";
              const picked = await pickJsonFile(startPath);
              if (!picked || !picked.path) {
                return;
              }
              this.values[fieldKey] = picked.path;
              if (fieldKey === "modelPath") {
                this.autoFillFromModelPath();
              }
              if (fieldKey === "spawnerPath") {
                this.autoFillSpawnerOut();
              }
            },
            calculateCombos() {
              try {
                this.comboSummary = calculateWizardCombinationPreview(this.values);
              } catch (error) {
                const message = error && error.message ? error.message : String(error);
                this.comboSummary = `Error: ${message}`;
              }
            },
            async previewFirstCombo() {
              if (this.previewBusy) {
                return;
              }
              this.previewBusy = true;
              this.previewStatus = "Rendering preview...";
              this.previewImageDataUrl = "";
              try {
                const preview = await renderWizardFirstComboPreview(this.values);
                this.previewImageDataUrl = preview.imageDataUrl;
                const comboPart = preview.comboSlug ? ` (${preview.comboSlug})` : "";
                this.previewStatus = `Preview ready${comboPart} at ${preview.iconSize}x${preview.iconSize}.`;
              } catch (error) {
                const message = error && error.message ? error.message : String(error);
                this.previewStatus = `Error: ${message}`;
              } finally {
                this.previewBusy = false;
              }
            }
          },
          template: `
            <div id="tw-spawner-wizard-layout">
              <div class="tw-card tw-full">
                <div class="tw-card-title">Source</div>
                <div class="tw-field">
                  <label>Model JSON Path</label>
                  <div class="tw-path-row">
                    <input type="text" v-model="values.modelPath" @change="autoFillFromModelPath" />
                    <button type="button" class="tool" @click="browsePath('modelPath')">Browse</button>
                  </div>
                </div>

                <div class="tw-field">
                  <label>Spawner JSON Path (optional)</label>
                  <div class="tw-path-row">
                    <input type="text" v-model="values.spawnerPath" @change="autoFillSpawnerOut" />
                    <button type="button" class="tool" @click="browsePath('spawnerPath')">Browse</button>
                  </div>
                </div>
                <div class="tw-field">
                  <label>Roles CSV (blank = auto)</label>
                  <input type="text" v-model="values.rolesCsv" />
                  <div class="tw-help">Comma-separated roles. Blank uses spawner allowlist or model name.</div>
                </div>
              </div>

              <div class="tw-card">
                <div class="tw-card-title">Variants</div>
                <div class="tw-field">
                  <label>Include Empty Sets CSV</label>
                  <input type="text" v-model="values.includeEmptySets" />
                </div>
                <div class="tw-field">
                  <label>Exclude Sets CSV (optional)</label>
                  <input type="text" v-model="values.excludeSetsCsv" />
                  <div class="tw-help">Comma-separated random attachment set categories to skip entirely.</div>
                </div>
                <div class="tw-field">
                  <label>Empty-State Token</label>
                  <input type="text" v-model="values.emptyValueToken" />
                </div>
                <div class="tw-field">
                  <label>Icon Output Dir (relative to Common/)</label>
                  <input type="text" v-model="values.iconRelDir" />
                </div>
                <div class="tw-field">
                  <label>Filename Template (blank = auto)</label>
                  <input type="text" v-model="values.filenameTemplate" />
                  <div class="tw-help">{model} {role} {combo_slug} {combo_index} {set_<setname>}</div>
                </div>
                <div class="tw-field">
                  <div class="tw-action-row">
                    <button type="button" class="tool" @click="calculateCombos">Calculate Combos</button>
                  </div>
                  <div class="tw-calc-summary" v-if="comboSummary">{{ comboSummary }}</div>
                </div>
              </div>

              <div class="tw-card">
                <div class="tw-card-title">Camera & Frame</div>
                <div class="tw-inline-pair">
                  <div class="tw-field">
                    <label>Icon Size</label>
                    <numeric-input v-model.number="values.iconSize" :min="16" :max="1024" :step="1" />
                  </div>
                  <div class="tw-field">
                    <label>Preview Zoom (Scale)</label>
                    <numeric-input v-model.number="values.cameraScale" :min="0.01" :max="20" :step="0.01" />
                  </div>
                </div>
                <div class="tw-inline-triple">
                  <div class="tw-field">
                    <label>Rotation X</label>
                    <numeric-input v-model.number="values.cameraRotationX" :min="-360" :max="360" :step="0.5" />
                  </div>
                  <div class="tw-field">
                    <label>Rotation Y</label>
                    <numeric-input v-model.number="values.cameraRotationY" :min="-360" :max="360" :step="0.5" />
                  </div>
                  <div class="tw-field">
                    <label>Rotation Z</label>
                    <numeric-input v-model.number="values.cameraRotationZ" :min="-360" :max="360" :step="0.5" />
                  </div>
                </div>
                <div class="tw-inline-pair">
                  <div class="tw-field">
                    <label>Position X</label>
                    <numeric-input v-model.number="values.cameraPositionX" :min="-256" :max="256" :step="1" />
                  </div>
                  <div class="tw-field">
                    <label>Position Y</label>
                    <numeric-input v-model.number="values.cameraPositionY" :min="-256" :max="256" :step="1" />
                  </div>
                </div>
                <div class="tw-inline-pair">
                  <div class="tw-check-row">
                    <input type="checkbox" id="tw_camera_auto_frame" v-model="values.cameraAutoFrame" />
                    <label for="tw_camera_auto_frame">Auto Frame</label>
                  </div>
                  <div class="tw-field">
                    <label>Auto Frame Padding</label>
                    <numeric-input v-model.number="values.cameraAutoFramePadding" :min="0" :max="32" :step="1" />
                  </div>
                </div>
                <div class="tw-field">
                  <div class="tw-action-row">
                    <button type="button" class="tool" @click="previewFirstCombo" :disabled="previewBusy">
                      {{ previewBusy ? "Rendering..." : "Preview First Combo" }}
                    </button>
                  </div>
                  <div class="tw-preview-panel">
                    <img v-if="previewImageDataUrl" :src="previewImageDataUrl" class="tw-preview-image" />
                    <div v-else class="tw-preview-empty">Preview appears here.</div>
                  </div>
                  <div class="tw-help" v-if="previewStatus">{{ previewStatus }}</div>
                </div>
              </div>

              <div class="tw-card tw-full">
                <div class="tw-card-title">Outputs</div>
                <div class="tw-inline-pair">
                  <div class="tw-check-row">
                    <input type="checkbox" id="tw_save_json" v-model="values.saveGeneratedJson" />
                    <label for="tw_save_json">Save Jobs + Manifest JSON</label>
                  </div>
                  <div class="tw-check-row">
                    <input type="checkbox" id="tw_write_spawner" v-model="values.writeSpawnerOverrides" />
                    <label for="tw_write_spawner">Write Spawner Overrides</label>
                  </div>
                  <div class="tw-check-row">
                    <input type="checkbox" id="tw_shared_role_group" v-model="values.sharedRoleGroup" />
                    <label for="tw_shared_role_group">Shared Role Group</label>
                  </div>
                </div>

                <div class="tw-field">
                  <label>Jobs JSON Output Path (optional)</label>
                  <div class="tw-path-row">
                    <input type="text" v-model="values.jobsOutPath" />
                    <button type="button" class="tool" @click="browsePath('jobsOutPath')">Browse</button>
                  </div>
                </div>

                <div class="tw-field">
                  <label>Manifest JSON Output Path (optional)</label>
                  <div class="tw-path-row">
                    <input type="text" v-model="values.manifestOutPath" />
                    <button type="button" class="tool" @click="browsePath('manifestOutPath')">Browse</button>
                  </div>
                </div>

                <div class="tw-inline-pair">
                  <div class="tw-check-row">
                    <input type="checkbox" id="tw_spawner_in_place" v-model="values.writeSpawnerInPlace" />
                    <label for="tw_spawner_in_place">Write Spawner In Place</label>
                  </div>
                  <div class="tw-field">
                    <label>IconDefault Override (optional)</label>
                    <input type="text" v-model="values.iconDefaultOverride" />
                  </div>
                </div>

                <div class="tw-field">
                  <label>Spawner Output Path (if not in place)</label>
                  <div class="tw-path-row">
                    <input type="text" v-model="values.spawnerOutPath" />
                    <button type="button" class="tool" @click="browsePath('spawnerOutPath')">Browse</button>
                  </div>
                </div>
              </div>
            </div>
          `
        },
        onConfirm() {
          const vm = dialog && dialog.content_vue ? dialog.content_vue : null;
          const values = vm && vm.values ? Object.assign({}, vm.values) : {};
          finish(values);
        },
        onCancel() {
          finish(null);
        }
      });
      dialog.show();
    });
  }

  function resolveUserPath(rawPath, baseDir) {
    const normalized = normalizePath(rawPath, baseDir);
    if (!normalized) {
      return null;
    }
    return getPathModule().normalize(normalized);
  }

  function normalizeRelDir(rawDir, fallback) {
    const normalized = String(rawDir || "")
      .replace(/\\/g, "/")
      .replace(/^\/+/g, "")
      .replace(/\/+$/g, "")
      .trim();
    return normalized || fallback;
  }

  function rememberWizardValues(values) {
    if (!values || typeof values !== "object") {
      return;
    }
    wizardLastValues = Object.assign({}, values);
  }

  function parseVectorText(raw, expectedCount, fallback) {
    if (typeof raw !== "string") {
      return fallback.slice();
    }
    const parts = raw
      .split(",")
      .map((part) => part.trim())
      .filter((part) => part.length > 0);
    if (parts.length !== expectedCount) {
      return fallback.slice();
    }
    const parsed = parts.map((part, idx) => asNumber(part, fallback[idx]));
    return parsed;
  }

  async function runWizardSubmission(formResult) {
    const path = getPathModule();
    const appRoot = path.resolve(".");

    const modelPath = resolveUserPath(formResult.modelPath, appRoot);
    if (!modelPath) {
      throw new Error("Model JSON path is required.");
    }
    if (!fileExists(modelPath)) {
      throw new Error(`Model JSON not found: ${modelPath}`);
    }
    const modelJson = readJsonFromDisk(modelPath);
    if (!modelJson || typeof modelJson !== "object") {
      throw new Error(`Model JSON is invalid: ${modelPath}`);
    }

    const modRoot = inferModRootFromServerPath(modelPath);
    const commonRoot = path.join(modRoot, "Common");
    const modelName = inferModelName(modelPath);

    const spawnerPathRaw = typeof formResult.spawnerPath === "string"
      ? formResult.spawnerPath.trim()
      : "";
    const spawnerPath = spawnerPathRaw ? resolveUserPath(spawnerPathRaw, modRoot) : null;
    let spawnerJson = null;
    if (spawnerPath) {
      if (!fileExists(spawnerPath)) {
        throw new Error(`Spawner JSON not found: ${spawnerPath}`);
      }
      spawnerJson = readJsonFromDisk(spawnerPath);
      if (!spawnerJson || typeof spawnerJson !== "object") {
        throw new Error(`Spawner JSON is invalid: ${spawnerPath}`);
      }
    }
    const assetRoots = buildAssetSearchRoots(modRoot, [modelPath, spawnerPath].filter(Boolean));

    const discoveredRoles = spawnerJson ? discoverRolesFromSpawner(spawnerJson) : [];
    const typedRoles = parseCsv(formResult.rolesCsv);
    const roles = typedRoles.length
      ? typedRoles
      : discoveredRoles.length
      ? discoveredRoles
      : [modelName];
    if (!roles.length) {
      throw new Error("At least one role is required.");
    }

    const setSelection = resolveSetSelectionConfig(
      modelJson,
      formResult.includeEmptySets,
      formResult.excludeSetsCsv
    );
    const defaultFilenameTemplate = `${modelName}_{combo_slug}.png`;

    const iconRelDir = normalizeRelDir(
      formResult.iconRelDir,
      "Icons/ItemsGenerated/Generated"
    );
    const filenameTemplate = String(formResult.filenameTemplate || "").trim() || defaultFilenameTemplate;
    const iconSize = Math.max(16, Math.floor(asNumber(formResult.iconSize, 64)));
    const cameraScale = Math.max(0.01, asNumber(formResult.cameraScale, 1.0));
    const legacyRotation = parseVectorText(String(formResult.cameraRotation || ""), 3, [22.5, 45.0, 22.5]);
    const legacyTranslation = parseVectorText(String(formResult.cameraTranslation || ""), 2, [0, 0]);
    const cameraRotation = [
      asNumber(formResult.cameraRotationX, legacyRotation[0]),
      asNumber(formResult.cameraRotationY, legacyRotation[1]),
      asNumber(formResult.cameraRotationZ, legacyRotation[2])
    ];
    const cameraTranslation = [
      asNumber(formResult.cameraPositionX, legacyTranslation[0]),
      asNumber(formResult.cameraPositionY, legacyTranslation[1])
    ];
    const includeEmptySets = setSelection.includeEmptySets;
    const excludeSets = setSelection.excludedSets;
    const emptyValueToken = String(formResult.emptyValueToken || "none").trim() || "none";

    const generated = buildGeneratedPayload({
      modelJson,
      modelPath,
      modRoot,
      commonRoot,
      roles,
      assetRoots,
      includeEmptySets,
      excludeSets,
      emptyValueToken,
      iconRelDir,
      filenameTemplate,
      iconSize,
      cameraScale,
      cameraRotation,
      cameraTranslation,
      cameraAutoFrame: formResult.cameraAutoFrame === true,
      cameraAutoFramePadding: Math.max(0, Math.floor(asNumber(formResult.cameraAutoFramePadding, 4))),
      cameraAutoFrameMaxAttempts: Math.max(1, Math.floor(asNumber(formResult.cameraAutoFrameMaxAttempts, 6))),
      sharedRoleGroup: formResult.sharedRoleGroup === true
    });
    const resolvedBaseModelFile = generated.jobsPayload
      && generated.jobsPayload.model
      ? generated.jobsPayload.model.baseModelFile
      : null;
    if (!resolvedBaseModelFile || !fileExists(resolvedBaseModelFile)) {
      const declaredModel = generated.jobsPayload && generated.jobsPayload.model
        ? generated.jobsPayload.model.baseModel
        : null;
      const rootsList = assetRoots.length ? assetRoots.map((root) => `- ${root}`).join("\n") : "- (none)";
      throw new Error(
        "Base model file not found.\n"
        + `ModelAsset.Model: ${declaredModel || "(missing)"}\n`
        + "Searched roots:\n"
        + rootsList
      );
    }

    let jobsOutPath = null;
    let manifestOutPath = null;
    if (formResult.saveGeneratedJson !== false) {
      const defaultJobsOut = path.join(modRoot, ".tmp", `${modelName}_spawner_render_jobs.json`);
      const defaultManifestOut = path.join(modRoot, ".tmp", `${modelName}_spawner_icon_manifest.json`);
      jobsOutPath = resolveUserPath(formResult.jobsOutPath, modRoot) || defaultJobsOut;
      manifestOutPath = resolveUserPath(formResult.manifestOutPath, modRoot) || defaultManifestOut;
      writeJson(jobsOutPath, generated.jobsPayload);
      writeJson(manifestOutPath, generated.manifest);
    }

    let spawnerOutPath = null;
    if (spawnerJson && formResult.writeSpawnerOverrides !== false) {
      const mergedSpawner = mergeOverridesIntoSpawner(
        spawnerJson,
        generated.roleOverrides,
        generated.iconOverrideGroups,
        String(formResult.iconDefaultOverride || "")
      );
      if (formResult.writeSpawnerInPlace !== false) {
        spawnerOutPath = spawnerPath;
      } else {
        spawnerOutPath =
          resolveUserPath(formResult.spawnerOutPath, modRoot) || replaceFileExt(spawnerPath, ".generated.json");
      }
      writeJson(spawnerOutPath, mergedSpawner);
    }

    const summary = await runBatchPayload(generated.jobsPayload, modRoot);
    const iconDirAbs = path.join(commonRoot, iconRelDir.replace(/\//g, path.sep));
    let message = buildRunSummaryText(summary);
    const comboCount = calculateComboCountFromSetDefinitions(setSelection.activeSetDefs);
    message += `\n\nPossible attachment combinations: ${formatIntegerWithSeparators(comboCount)}`;
    if (excludeSets.length) {
      message += `\nExcluded sets: ${excludeSets.join(", ")}`;
    }
    if (setSelection.includeIgnoredBecauseExcluded.length) {
      message +=
        `\nIgnored include-empty entries (excluded): `
        + setSelection.includeIgnoredBecauseExcluded.join(", ");
    }
    message += `\n\nIcon directory:\n- ${iconDirAbs}`;
    if (jobsOutPath || manifestOutPath || spawnerOutPath) {
      message += "\n\nOutputs:";
      if (jobsOutPath) message += `\n- Jobs: ${jobsOutPath}`;
      if (manifestOutPath) message += `\n- Manifest: ${manifestOutPath}`;
      if (spawnerOutPath) message += `\n- Spawner: ${spawnerOutPath}`;
    }
    await showTextDialog("Spawner Icon Wizard", message, 980);
  }

  async function runWizardFlow() {
    requireDesktopApp();
    while (true) {
      const formResult = await showWizardConfigDialog(getWizardDefaults());
      if (!formResult) {
        return;
      }
      rememberWizardValues(formResult);
      try {
        await runWizardSubmission(formResult);
        return;
      } catch (error) {
        await showError(error);
        console.error(`[${PLUGIN_ID}] Wizard submission failed`, error);
      }
    }
  }

  function askYesNo(title, message, yesLabel, noLabel) {
    return new Promise((resolve) => {
      Blockbench.showMessageBox(
        {
          title,
          message,
          buttons: [yesLabel || "Yes", noLabel || "No"],
          confirmIndex: 0,
          cancelIndex: 1
        },
        (choice) => {
          resolve(choice === 0);
        }
      );
    });
  }

  function askText(title, message, defaultValue) {
    return new Promise((resolve) => {
      if (typeof Dialog === "undefined") {
        resolve(null);
        return;
      }
      const dialogId = `tw_spawner_wizard_input_${Date.now()}_${Math.floor(Math.random() * 1e6)}`;
      let dialog = null;
      const finish = (value) => {
        try {
          if (dialog) {
            dialog.hide();
          }
        } catch (_error) {}
        try {
          if (dialog && typeof dialog.delete === "function") {
            dialog.delete();
          }
        } catch (_error) {}
        resolve(value);
      };
      dialog = new Dialog({
        id: dialogId,
        title,
        form: {
          value: {
            label: message,
            type: "text",
            value: defaultValue || ""
          }
        },
        onConfirm(formResult) {
          const value =
            formResult && Object.prototype.hasOwnProperty.call(formResult, "value")
              ? String(formResult.value)
              : "";
          finish(value);
        },
        onCancel() {
          finish(null);
        }
      });
      dialog.show();
    });
  }

  async function runFromPrompt() {
    if (isRunning) {
      Blockbench.showQuickMessage("Spawner icon batch renderer is already running.");
      return;
    }
    isRunning = true;
    try {
      requireDesktopApp();
      const file = await pickJsonFile("");
      if (!file || !file.path) {
        return;
      }
      await runBatch(file.path);
    } catch (error) {
      await showError(error);
      console.error(`[${PLUGIN_ID}]`, error);
    } finally {
      isRunning = false;
    }
  }

  async function runWizard() {
    if (isRunning) {
      Blockbench.showQuickMessage("Spawner icon batch renderer is already running.");
      return;
    }
    isRunning = true;
    try {
      await runWizardFlow();
    } catch (error) {
      await showError(error);
      console.error(`[${PLUGIN_ID}]`, error);
    } finally {
      isRunning = false;
    }
  }

  BBPlugin.register(PLUGIN_ID, {
    title: "Tamework Spawner Icon Batch Renderer",
    author: "Alec + Codex",
    icon: "view_in_ar",
    description:
      "Render spawner item icons in bulk from generate_spawner_icon_overrides.py renderer-jobs JSON.",
    version: "0.2.0",
    variant: "desktop",
    min_version: "5.0.5",
    onload() {
      runAction = new Action(ACTION_ID, {
        name: "Run Tamework Spawner Batch (From Jobs JSON)",
        icon: "view_in_ar",
        description: "Select a renderer jobs JSON file and batch-render icons.",
        condition: () => true,
        click() {
          runFromPrompt();
        }
      });
      wizardAction = new Action(ACTION_WIZARD_ID, {
        name: "Generate + Run Tamework Spawner Batch Wizard",
        icon: "tune",
        description:
          "Pick model/spawner, configure roles and camera, then generate and render icons in one flow.",
        condition: () => true,
        click() {
          runWizard();
        }
      });
      if (MenuBar && MenuBar.menus && MenuBar.menus.tools && MenuBar.menus.tools.addAction) {
        MenuBar.menus.tools.addAction(runAction);
        MenuBar.menus.tools.addAction(wizardAction);
      }
    },
    onunload() {
      if (runAction) {
        runAction.delete();
        runAction = null;
      }
      if (wizardAction) {
        wizardAction.delete();
        wizardAction = null;
      }
    }
  });
})();
