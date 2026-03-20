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

  function inferModRootFromServerPath(serverPath) {
    const normalized = String(serverPath || "");
    const marker = `${getPathModule().sep}Server${getPathModule().sep}`;
    const index = normalized.toLowerCase().indexOf(marker.toLowerCase());
    if (index === -1) {
      throw new Error(
        `Could not infer mod root from path (expected ...${marker}...): ${serverPath}`
      );
    }
    return normalized.slice(0, index);
  }

  function resolveCommonAssetFile(commonRoot, assetPath) {
    if (!assetPath || typeof assetPath !== "string") {
      return null;
    }
    return normalizePath(assetPath, commonRoot);
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

  function waitFrame() {
    return new Promise((resolve) => {
      setTimeout(resolve, 0);
    });
  }

  function getTextureByPath(texturePath) {
    if (typeof Texture === "undefined" || !Array.isArray(Texture.all)) {
      return null;
    }
    return Texture.all.find((texture) => texture && texture.path === texturePath) || null;
  }

  function loadTextureFromPath(texturePath) {
    if (!texturePath || !fileExists(texturePath)) {
      return null;
    }
    let texture = getTextureByPath(texturePath);
    if (!texture) {
      texture = new Texture().fromPath(texturePath).add(false, true);
    }
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
    if (typeof Canvas !== "undefined" && typeof Canvas.updateAllFaces === "function") {
      Canvas.updateAllFaces(texture);
    }
    return texture;
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
    if (typeof Canvas !== "undefined" && typeof Canvas.updateAllFaces === "function") {
      Canvas.updateAllFaces(texture);
    }
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
    return new Promise((resolve) => {
      const message = error && error.message ? error.message : String(error);
      Blockbench.showMessageBox(
        {
          title: "Spawner Icon Batch Renderer",
          message
        },
        () => {
          resolve();
        }
      );
    });
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

  function extractSetDefinitions(modelJson, includeEmptySets) {
    const randomSets = modelJson && modelJson.RandomAttachmentSets;
    if (!randomSets || typeof randomSets !== "object") {
      throw new Error("Model JSON does not define RandomAttachmentSets.");
    }
    const includeSet = new Set(includeEmptySets || []);
    const result = [];
    Object.keys(randomSets).forEach((setName) => {
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

  function mergeOverridesIntoSpawner(spawnerJson, roleOverrides, iconDefault) {
    const output = Object.assign({}, spawnerJson || {});
    const existing =
      output.IconOverridesByRole && typeof output.IconOverridesByRole === "object"
        ? output.IconOverridesByRole
        : {};
    const merged = Object.assign({}, existing);
    Object.keys(roleOverrides).forEach((role) => {
      merged[role] = roleOverrides[role];
    });
    output.IconOverridesByRole = merged;
    if (typeof iconDefault === "string" && iconDefault.trim().length) {
      output.IconDefault = iconDefault.trim();
    }
    return output;
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
    const roles = config.roles;
    const setDefs = extractSetDefinitions(modelJson, config.includeEmptySets);
    const optionVisuals = extractOptionVisuals(modelJson);
    const baseModel = typeof modelJson.Model === "string" ? modelJson.Model : null;
    const baseTexture = typeof modelJson.Texture === "string" ? modelJson.Texture : null;

    const optionSpace = setDefs.map((setDef) => setDef.options);
    const combos = cartesianProduct(optionSpace);
    const roleOverrides = {};
    roles.forEach((role) => {
      roleOverrides[role] = [];
    });

    const jobs = [];
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
      const comboSlug = slugParts.join("__");

      const commonPlaceholders = {
        model: modelName,
        combo_index: String(comboIndex),
        combo_slug: comboSlug
      };
      Object.keys(setValues).forEach((setName) => {
        const value = setValues[setName];
        commonPlaceholders[safeKey(setName)] = value;
      });

      const iconsByRole = {};
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
            modelFile: resolveCommonAssetFile(commonRoot, visual ? visual.model : null),
            textureFile: resolveCommonAssetFile(commonRoot, visual ? visual.texture : null)
          });
        });

        jobs.push({
          id: `${comboSlug}__role_${role}`,
          role,
          comboIndex,
          comboSlug,
          attachments,
          setValues,
          baseModel,
          baseTexture,
          baseModelFile: resolveCommonAssetFile(commonRoot, baseModel),
          baseTextureFile: resolveCommonAssetFile(commonRoot, baseTexture),
          selectedOptionAssets,
          outputIcon: iconRel,
          outputIconFile: iconFile
        });
      });

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
      manifest: {
        schema: "tamework.spawner-icon-manifest.v1",
        generatedAtUtc: new Date().toISOString(),
        modRoot,
        modelPath,
        roles,
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
            translation: config.cameraTranslation
          }
        },
        model: {
          baseModel,
          baseTexture,
          baseModelFile: resolveCommonAssetFile(commonRoot, baseModel),
          baseTextureFile: resolveCommonAssetFile(commonRoot, baseTexture)
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
      ensureFacesTextured(texture);
      await waitFrame();
    }
  }

  function buildAttachmentCollection(name, content, modelPath, texturePath) {
    if (typeof Collection === "undefined" || !content || !Array.isArray(content.new_groups)) {
      return;
    }
    const newGroups = content.new_groups;
    if (!newGroups.length) {
      return;
    }
    const rootGroups = newGroups.filter((group) => !newGroups.includes(group.parent));
    const collection = new Collection({
      name,
      children: rootGroups.map((group) => group.uuid),
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
    if (texture && texture.uuid) {
      collection.texture = texture.uuid;
    }
  }

  function selectEffectiveBase(job, baseModelPath, baseTexturePath, jobsDir) {
    const assets = Array.isArray(job.selectedOptionAssets) ? job.selectedOptionAssets : [];
    if (assets.length !== 1) {
      return {
        modelPath: baseModelPath,
        texturePath: baseTexturePath,
        consumedAssetIndex: -1
      };
    }
    const asset = assets[0];
    const modelPath = normalizePath(asset && asset.modelFile, jobsDir);
    const texturePath = normalizePath(asset && asset.textureFile, jobsDir) || baseTexturePath;
    if (!modelPath || modelPath === baseModelPath) {
      return {
        modelPath: baseModelPath,
        texturePath: baseTexturePath,
        consumedAssetIndex: -1
      };
    }
    const baseName = getPathModule().basename(modelPath).toLowerCase();
    if (baseName.includes("attachment")) {
      return {
        modelPath: baseModelPath,
        texturePath: baseTexturePath,
        consumedAssetIndex: -1
      };
    }
    return {
      modelPath,
      texturePath,
      consumedAssetIndex: 0
    };
  }

  async function applyAttachments(codec, job, jobsDir, baseModelPath, consumedAssetIndex) {
    const assets = Array.isArray(job.selectedOptionAssets) ? job.selectedOptionAssets : [];
    let baseTextureOverride = null;
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
      if (modelPath === baseModelPath) {
        if (texturePath) {
          baseTextureOverride = texturePath;
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
      buildAttachmentCollection(attachmentName, parseResult, modelPath, texturePath);
      await waitFrame();
    }
    return baseTextureOverride;
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
      const focusSize = Math.max(
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
      const desiredDistance = (focusSize * 1.2) / Math.max(scale, 0.001);
      preview.camera.position.multiplyScalar(desiredDistance / safeDistance);
    }

    if (preview.controls && typeof preview.controls.update === "function") {
      preview.controls.update();
    }
    if (preview.camera && typeof preview.camera.updateProjectionMatrix === "function") {
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

  async function renderSingleJob(codec, payloadDefaults, job, jobsDir) {
    const baseModelPath = normalizePath(job.baseModelFile, jobsDir);
    const baseTexturePath = normalizePath(job.baseTextureFile, jobsDir);
    const outputPath = normalizePath(job.outputIconFile, jobsDir);
    if (!baseModelPath) {
      throw new Error(`Job missing baseModelFile: ${JSON.stringify(job.id || job.comboSlug || job)}`);
    }
    if (!outputPath) {
      throw new Error(`Job missing outputIconFile: ${JSON.stringify(job.id || job.comboSlug || job)}`);
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
    await loadBaseModel(codec, effectiveBase.modelPath, effectiveBase.texturePath);
    const baseTextureOverride = await applyAttachments(
      codec,
      job,
      jobsDir,
      effectiveBase.modelPath,
      effectiveBase.consumedAssetIndex
    );
    if (baseTextureOverride) {
      const texture = setDefaultTexture(baseTextureOverride);
      ensureFacesTextured(texture);
      await waitFrame();
    }
    const preview = choosePreview();
    if (!preview) {
      throw new Error("Could not resolve an active Blockbench preview.");
    }
    if (typeof preview.resize === "function") {
      preview.resize(iconSize, iconSize);
    }
    applyCamera(preview, payloadDefaults, job);
    await waitFrame();

    let imageDataUrl = await captureScreenshot(preview, iconSize);
    imageDataUrl = await applyScreenTranslation(imageDataUrl, iconSize, translation);
    await assertImageNotFullyTransparent(imageDataUrl, iconSize, buildSceneDiagnostics(preview));

    ensureDirectory(outputPath);
    await writeImage(outputPath, imageDataUrl);
    return outputPath;
  }

  async function runBatchPayload(payload, jobsDir) {
    const codec = requireHytaleCodec();
    const total = payload.jobs.length;
    const defaults = payload.defaults && typeof payload.defaults === "object" ? payload.defaults : {};
    const failures = [];
    const startedAt = Date.now();

    for (let i = 0; i < total; i += 1) {
      const job = payload.jobs[i];
      const label = typeof job.id === "string" ? job.id : `job-${i + 1}`;
      Blockbench.setProgress((i + 1) / total);
      try {
        await renderSingleJob(codec, defaults, job, jobsDir);
      } catch (error) {
        failures.push({
          index: i + 1,
          id: label,
          message: error && error.message ? error.message : String(error)
        });
        console.error(`[${PLUGIN_ID}] Failed ${label}`, error);
      }
    }

    Blockbench.setProgress();
    const elapsedSec = ((Date.now() - startedAt) / 1000).toFixed(1);
    const successCount = total - failures.length;
    return {
      total,
      successCount,
      failures,
      elapsedSec
    };
  }

  function buildRunSummaryText(summary) {
    let message = `Completed ${summary.successCount}/${summary.total} render jobs in ${summary.elapsedSec}s.`;
    if (summary.failures.length) {
      const sample = summary.failures
        .slice(0, 8)
        .map((entry) => `#${entry.index} ${entry.id}: ${entry.message}`)
        .join("\n");
      message += `\n\nFailures: ${summary.failures.length}\n${sample}`;
    }
    return message;
  }

  async function runBatch(jobsFilePath) {
    const path = getPathModule();
    const jobsDir = path.dirname(jobsFilePath);
    const payload = parseJobsPayload(readJsonFromDisk(jobsFilePath));
    const summary = await runBatchPayload(payload, jobsDir);
    Blockbench.showMessageBox({
      title: "Spawner Icon Batch Renderer",
      message: buildRunSummaryText(summary)
    });
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
      saveGeneratedJson: true,
      jobsOutPath: "",
      manifestOutPath: "",
      writeSpawnerOverrides: true,
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
    `;
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
              values: Object.assign({}, defaults)
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

    const setDefs = extractSetDefinitions(modelJson, []);
    const primarySet = setDefs.length === 1 ? setDefs[0] : null;
    const defaultFilenameTemplate = primarySet
      ? `${modelName}_{role}_{${safeKey(primarySet.name)}}.png`
      : `${modelName}_{role}_{combo_slug}.png`;

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
    const includeEmptySets = parseCsv(formResult.includeEmptySets);
    const emptyValueToken = String(formResult.emptyValueToken || "none").trim() || "none";

    const generated = buildGeneratedPayload({
      modelJson,
      modelPath,
      modRoot,
      commonRoot,
      roles,
      includeEmptySets,
      emptyValueToken,
      iconRelDir,
      filenameTemplate,
      iconSize,
      cameraScale,
      cameraRotation,
      cameraTranslation
    });

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
    message += `\n\nIcon directory:\n- ${iconDirAbs}`;
    if (jobsOutPath || manifestOutPath || spawnerOutPath) {
      message += "\n\nOutputs:";
      if (jobsOutPath) message += `\n- Jobs: ${jobsOutPath}`;
      if (manifestOutPath) message += `\n- Manifest: ${manifestOutPath}`;
      if (spawnerOutPath) message += `\n- Spawner: ${spawnerOutPath}`;
    }
    Blockbench.showMessageBox({
      title: "Spawner Icon Wizard",
      message
    });
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
