(function () {
  const repoApi = "https://api.github.com/repos/wan300/bjtu_mis_Android/releases?per_page=100";
  const releaseListingUrl = "https://github.com/wan300/bjtu_mis_Android/releases";
  const fallbackReleases = [
    {
      tag_name: "v1.4.3",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.4.3",
      prerelease: false,
      published_at: "2026-10-19T18:28:48Z",
      assets: [
        {
          name: "BJTU-MIS-v1.4.3.apk",
          size: 213933263,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.4.3/BJTU-MIS-v1.4.3.apk"
        }
      ]
    },

    {
      tag_name: "v1.4.2",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.4.2",
      prerelease: false,
      published_at: "2026-09-09T03:56:14Z",
      assets: [
        {
          name: "BJTU-MIS-v1.4.2.apk",
          size: 151663841,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.4.2/BJTU-MIS-v1.4.2.apk"
        }
      ]
    },
    {
      tag_name: "v1.3.1",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.3.1",
      prerelease: false,
      published_at: "2026-06-24T17:08:04Z",
      assets: [
        {
          name: "app-release.apk",
          size: 212961406,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.3.1/app-release.apk"
        }
      ]
    },
    {
      tag_name: "v1.3.0-beta.1",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.3.0-beta.1",
      prerelease: true,
      published_at: "2026-06-11T07:24:58Z",
      assets: [
        {
          name: "app-release.apk",
          size: 212933899,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.3.0-beta.1/app-release.apk"
        }
      ]
    },
    {
      tag_name: "v1.2.1",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.2.1",
      prerelease: false,
      published_at: "2026-06-06T03:53:14Z",
      assets: [
        {
          name: "app-release.apk",
          size: 212634249,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.2.1/app-release.apk"
        }
      ]
    },
    {
      tag_name: "v1.2.0",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.2.0",
      prerelease: false,
      published_at: "2026-06-03T05:37:49Z",
      assets: [
        {
          name: "app-release.apk",
          size: 212601385,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.2.0/app-release.apk"
        }
      ]
    },
    {
      tag_name: "v1.1.1",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.1.1",
      prerelease: false,
      published_at: "2026-05-30T17:00:38Z",
      assets: [
        {
          name: "app-release.apk",
          size: 212255877,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.1.1/app-release.apk"
        }
      ]
    },
    {
      tag_name: "v1.1.0",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.1.0",
      prerelease: false,
      published_at: "2026-05-21T14:09:21Z",
      assets: [
        {
          name: "app-release.apk",
          size: 212222513,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.1.0/app-release.apk"
        }
      ]
    },
    {
      tag_name: "v1.0.0",
      html_url: "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.0.0",
      prerelease: false,
      published_at: "2026-05-18T16:51:57Z",
      assets: [
        {
          name: "app-release.apk",
          size: 223256028,
          browser_download_url:
            "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.0.0/app-release.apk"
        }
      ]
    }
  ];
  const fallbackApk = "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.4.3/BJTU-MIS-v1.4.3.apk";
  const fallbackRelease = "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.4.3";
  const fallbackTag = "v1.4.3";
  const fallbackSizeText = "204.02 MB";
  const releaseRefreshInterval = 5 * 60 * 1000;
  const imageAssetQuery = "?v=2026062602";

  const moduleData = window.BJTU_MODULES || [];
  const isModulePage = document.body.dataset.page === "module";
  const rootPrefix = isModulePage ? "../" : "";
  const assetPrefix = `${rootPrefix}assets/`;

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function iconPath(icon) {
    return `${assetPrefix}icons/${icon}${imageAssetQuery}`;
  }

  function moduleLink(slug) {
    return isModulePage ? `${slug}.html` : `modules/${slug}.html`;
  }

  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return "";
    const units = ["B", "KB", "MB", "GB"];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
  }

  function formatDate(value) {
    if (!value) return "--";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "--";
    return new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit"
    }).format(date).replace(/\//g, "-");
  }

  function categoryGroups() {
    const groups = new Map();
    moduleData.forEach((item) => {
      if (!groups.has(item.category)) groups.set(item.category, []);
      groups.get(item.category).push(item);
    });
    return Array.from(groups.entries());
  }

  function initNav() {
    const toggle = document.querySelector("[data-nav-toggle]");
    if (!toggle) return;
    toggle.addEventListener("click", () => {
      const open = document.body.classList.toggle("nav-open");
      toggle.setAttribute("aria-expanded", String(open));
    });
  }

  function renderHomeModules() {
    const container = document.querySelector("[data-module-grid]");
    if (!container) return;

    container.innerHTML = moduleData
      .map(
        (item) => `
          <a class="module-card reveal" href="${moduleLink(item.slug)}" style="--accent:${item.accent}">
            <span class="module-card__marker">${escapeHtml(item.category)}</span>
            <img src="${iconPath(item.icon)}" alt="" loading="lazy" width="72" height="72">
            <span class="module-card__title">${escapeHtml(item.title)}</span>
            <span class="module-card__subtitle">${escapeHtml(item.subtitle)}</span>
          </a>
        `
      )
      .join("");
  }

  function renderCategoryRails() {
    const container = document.querySelector("[data-category-rails]");
    if (!container) return;
    container.innerHTML = categoryGroups()
      .map(
        ([category, items]) => `
          <section class="module-band reveal" aria-labelledby="category-${escapeHtml(category)}">
            <div>
              <p class="eyebrow">Module line</p>
              <h3 id="category-${escapeHtml(category)}">${escapeHtml(category)}</h3>
            </div>
            <div class="module-band__links">
              ${items
                .map(
                  (item) => `
                    <a href="${moduleLink(item.slug)}" style="--accent:${item.accent}">
                      <img src="${iconPath(item.icon)}" alt="" loading="lazy" width="40" height="40">
                      <span>${escapeHtml(item.title)}</span>
                    </a>
                  `
                )
                .join("")}
            </div>
          </section>
        `
      )
      .join("");
  }

  function renderModulePage() {
    const slug = document.body.dataset.module;
    const root = document.querySelector("[data-module-root]");
    if (!slug || !root) return;

    const index = moduleData.findIndex((item) => item.slug === slug);
    const item = moduleData[index];
    if (!item) {
      root.innerHTML = `
        <section class="module-page module-page--missing">
          <p class="eyebrow">Not found</p>
          <h1>模块不存在</h1>
          <p>请返回主页重新选择模块。</p>
          <a class="btn btn--primary" href="../index.html">返回主页</a>
        </section>
      `;
      return;
    }

    const previous = moduleData[(index - 1 + moduleData.length) % moduleData.length];
    const next = moduleData[(index + 1) % moduleData.length];
    document.title = `${item.title} - BJTU MIS Android`;

    root.innerHTML = `
      <section class="subhero" style="--accent:${item.accent}">
        <div class="subhero__copy reveal">
          <a class="back-link" href="../index.html">返回主页</a>
          <p class="eyebrow">${escapeHtml(item.category)}</p>
          <h1>${escapeHtml(item.title)}</h1>
          <p>${escapeHtml(item.summary)}</p>
          <div class="subhero__actions">
            <a class="btn btn--primary" href="../index.html#download">下载应用</a>
            <a class="btn btn--ghost" href="../index.html#modules">浏览全部模块</a>
          </div>
        </div>
        <div class="subhero__visual reveal">
          <span class="subhero__sign">${escapeHtml(item.key)}</span>
          <img class="subhero__icon" src="${iconPath(item.icon)}" alt="" width="180" height="180">
        </div>
      </section>

      <section class="content-section content-section--tight">
        <div class="section-heading reveal">
          <p class="eyebrow">Use case</p>
          <h2>适用场景</h2>
          <p>${escapeHtml(item.scenario)}</p>
        </div>
        <div class="ability-grid">
          ${item.abilities
            .map(
              (ability, abilityIndex) => `
                <article class="ability-card reveal" style="--accent:${item.accent}">
                  <span>${String(abilityIndex + 1).padStart(2, "0")}</span>
                  <p>${escapeHtml(ability)}</p>
                </article>
              `
            )
            .join("")}
        </div>
      </section>

      <section class="module-nav reveal">
        <a href="${moduleLink(previous.slug)}" style="--accent:${previous.accent}">
          <span>上一站</span>
          <strong>${escapeHtml(previous.title)}</strong>
        </a>
        <a href="${moduleLink(next.slug)}" style="--accent:${next.accent}">
          <span>下一站</span>
          <strong>${escapeHtml(next.title)}</strong>
        </a>
      </section>
    `;
  }

  function initDownload() {
    const buttons = Array.from(document.querySelectorAll("[data-download-button]"));
    const buttonLabels = Array.from(document.querySelectorAll("[data-download-label]"));
    const releaseLinks = Array.from(document.querySelectorAll("[data-release-link]"));
    const releaseSelects = Array.from(document.querySelectorAll("[data-release-select]"));
    const versions = Array.from(document.querySelectorAll("[data-release-version]"));
    const sizes = Array.from(document.querySelectorAll("[data-release-size]"));
    const channels = Array.from(document.querySelectorAll("[data-release-channel]"));
    const published = Array.from(document.querySelectorAll("[data-release-published]"));
    const availability = Array.from(document.querySelectorAll("[data-release-availability]"));
    let lastReleaseFetch = 0;
    let releaseFetchInFlight = null;
    let selectedTag = fallbackTag;
    let releases = normalizeReleases(fallbackReleases);

    buttons.forEach((button) => {
      button.href = fallbackApk;
      button.dataset.version = fallbackTag;
    });
    releaseLinks.forEach((link) => {
      link.href = fallbackRelease;
    });
    versions.forEach((node) => {
      node.textContent = fallbackTag;
    });
    sizes.forEach((node) => {
      node.textContent = fallbackSizeText;
    });
    channels.forEach((node) => {
      node.textContent = "正式版";
    });
    published.forEach((node) => {
      node.textContent = "--";
    });
    availability.forEach((node) => {
      node.textContent = "未提供 APK";
      node.classList.add("is-unavailable");
    });

    if (!buttons.length && !releaseLinks.length && !releaseSelects.length) return;

    function findApkAsset(assets) {
      if (!Array.isArray(assets)) return null;
      const installableApks = assets.filter(
        (asset) => /\.apk$/i.test(asset.name || "") && !/unsigned/i.test(asset.name || "")
      );
      return installableApks.find((asset) => asset.name === "app-release.apk") || installableApks[0] || null;
    }

    function normalizeRelease(release) {
      const tag = typeof release.tag_name === "string" ? release.tag_name.trim() : "";
      if (!tag) return null;
      const apk = findApkAsset(release.assets);
      return {
        tag,
        htmlUrl: release.html_url || `${releaseListingUrl}/tag/${tag}`,
        prerelease: Boolean(release.prerelease),
        releasePageOnly: Boolean(release.release_page_only),
        publishedAt: release.published_at || release.created_at || "",
        apkUrl: apk && apk.browser_download_url ? apk.browser_download_url : "",
        apkSize: apk && Number.isFinite(apk.size) ? apk.size : 0,
        hasApk: Boolean(apk && apk.browser_download_url)
      };
    }

    function normalizeReleases(source) {
      if (!Array.isArray(source)) return [];
      const seen = new Set();
      return source
        .map(normalizeRelease)
        .filter((release) => release && !seen.has(release.tag) && seen.add(release.tag))
        .sort((left, right) => {
          const leftTime = new Date(left.publishedAt || 0).getTime();
          const rightTime = new Date(right.publishedAt || 0).getTime();
          return rightTime - leftTime;
        });
    }

    function defaultRelease(source) {
      return source.find((release) => !release.prerelease) || source[0] || null;
    }

    function currentRelease(source, preferredTag) {
      return source.find((release) => release.tag === preferredTag) || defaultRelease(source);
    }

    function optionLabel(release) {
      return `${release.tag} · ${release.prerelease ? "预发布" : "正式版"} · ${formatDate(release.publishedAt)}`;
    }

    function syncSelects(source, currentTag) {
      releaseSelects.forEach((select) => {
        select.innerHTML = source
          .map(
            (release) =>
              `<option value="${escapeHtml(release.tag)}">${escapeHtml(optionLabel(release))}</option>`
          )
          .join("");
        select.value = currentTag;
      });
    }

    function setButtons(release) {
      const downloadUrl = release.hasApk ? release.apkUrl : release.htmlUrl || releaseListingUrl;
      const isAvailable = release.hasApk || release.releasePageOnly;
      buttons.forEach((button) => {
        button.href = downloadUrl;
        button.dataset.version = release.tag;
        button.setAttribute("aria-disabled", String(!isAvailable));
        button.classList.toggle("is-disabled", !isAvailable);
      });
      buttonLabels.forEach((label) => {
        label.textContent = release.hasApk
          ? "下载所选 APK"
          : release.releasePageOnly
            ? "查看最新发布页"
            : "当前版本无 APK";
      });
      availability.forEach((node) => {
        node.textContent = release.hasApk
          ? "可下载"
          : release.releasePageOnly
            ? "等待 GitHub 发布"
            : "未提供 APK";
        node.classList.toggle("is-unavailable", !release.hasApk);
      });
    }

    function applyRelease(release, fallbackMode) {
      if (!release) return;
      const releaseUrl = release.htmlUrl || fallbackRelease;
      const sizeText = release.apkSize ? formatBytes(release.apkSize) : fallbackSizeText;

      setButtons(release);
      releaseLinks.forEach((link) => {
        link.href = releaseUrl;
      });
      versions.forEach((node) => {
        node.textContent = release.tag;
      });
      sizes.forEach((node) => {
        node.textContent = sizeText;
      });
      channels.forEach((node) => {
        node.textContent = release.prerelease ? "预发布" : "正式版";
      });
      published.forEach((node) => {
        node.textContent = formatDate(release.publishedAt);
      });
      syncSelects(releases, release.tag);
      document.body.classList.toggle("release-fallback", Boolean(fallbackMode));
    }

    function applyReleaseSet(source, preferredTag, fallbackMode) {
      const release = currentRelease(source, preferredTag);
      if (!release) return;
      selectedTag = release.tag;
      applyRelease(release, fallbackMode);
    }

    function syncRelease(force) {
      const now = Date.now();
      if (!force && now - lastReleaseFetch < releaseRefreshInterval) return releaseFetchInFlight;
      if (releaseFetchInFlight) return releaseFetchInFlight;

      lastReleaseFetch = now;
      releaseFetchInFlight = fetch(repoApi, {
        cache: "no-store",
        headers: { Accept: "application/vnd.github+json" }
      })
        .then((response) => {
          if (!response.ok) throw new Error(`GitHub API ${response.status}`);
          return response.json();
        })
        .then((payload) => {
          const hasPreferredRelease = Array.isArray(payload)
            ? payload.some((release) => release && release.tag_name === fallbackTag)
            : false;
          const source = hasPreferredRelease ? payload : [fallbackReleases[0], ...payload];
          const liveReleases = normalizeReleases(source);
          if (!liveReleases.length) throw new Error("GitHub API returned no releases");
          releases = liveReleases;
          applyReleaseSet(releases, selectedTag, false);
        })
        .catch(() => {
          releases = normalizeReleases(fallbackReleases);
          applyReleaseSet(releases, selectedTag, true);
        })
        .finally(() => {
          releaseFetchInFlight = null;
        });

      return releaseFetchInFlight;
    }

    syncSelects(releases, selectedTag);
    applyReleaseSet(releases, selectedTag, false);
    releaseSelects.forEach((select) => {
      select.addEventListener("change", (event) => {
        selectedTag = event.target.value || selectedTag;
        applyReleaseSet(releases, selectedTag, document.body.classList.contains("release-fallback"));
      });
    });
    document.addEventListener("click", (event) => {
      const disabledButton = event.target.closest("[data-download-button][aria-disabled='true']");
      if (disabledButton) event.preventDefault();
    });
    syncRelease(true);

    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible") syncRelease(false);
    });
    window.addEventListener("focus", () => syncRelease(false));
    window.addEventListener("pageshow", () => syncRelease(false));
  }

  function initReveal() {
    const items = Array.from(document.querySelectorAll(".reveal"));
    if (!items.length) return;
    if (!("IntersectionObserver" in window) || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      items.forEach((item) => item.classList.add("is-visible"));
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.16 }
    );

    items.forEach((item) => observer.observe(item));
  }

  function initYear() {
    document.querySelectorAll("[data-current-year]").forEach((node) => {
      node.textContent = String(new Date().getFullYear());
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    initNav();
    renderHomeModules();
    renderCategoryRails();
    renderModulePage();
    initDownload();
    initReveal();
    initYear();
  });
})();
