/* Public loading UI only. Never store sessions, account data or credentials here. */
(() => {
  "use strict";
  const shell = document.getElementById("portal-boot");
  const portal = document.getElementById("portal");
  const brand = document.getElementById("boot-brand");
  const message = document.getElementById("boot-message");
  const status = document.getElementById("boot-status");
  const retry = document.getElementById("boot-retry");
  const entities = {
    "lsp-nusantara.demo.inoerawan.id": ["LSP Nusantara", "Credential issuer", "LSP Teknik Ketenagalistrikan Nusantara (demo)", "#5140b5", "#f6f5fc"],
    "karya-instalasi.demo.inoerawan.id": ["Karya Instalasi", "Employer & verifier", "PT Karya Instalasi Nusantara (demo)", "#a34720", "#faf7f2"]
  };
  const entity = entities[location.hostname] || ["NusaCred Wallet", "Wallet provider", "PT Dompet Identitas Nusantara (demo)", "#087b69", "#f5f6f2"];
  brand.textContent = entity[0];
  document.getElementById("boot-role").textContent = entity[1];
  document.getElementById("boot-owner").textContent = entity[2] + " · Synthetic demonstration";
  document.documentElement.style.setProperty("--accent", entity[3]);
  document.documentElement.style.setProperty("--paper", entity[4]);
  document.querySelector('meta[name="theme-color"]').content = entity[3];
  let completed = false;
  let waitingForFrame = false;
  function problem(text) {
    if (completed) return;
    message.textContent = text;
    status.textContent = "Waiting for the application";
    retry.hidden = false;
  }
  function ready() {
    const canvas = portal.querySelector("canvas");
    // The application sets its entity label after Compose mounts. Canvas
    // insertion alone happens too early, before the renderer is initialized.
    if (completed || waitingForFrame || !canvas || canvas.width === 0 ||
        portal.getAttribute("aria-label") === "Competency workspace") return;
    waitingForFrame = true;
    requestAnimationFrame(() => requestAnimationFrame(() => {
      completed = true;
      shell.hidden = true;
      portal.removeAttribute("aria-busy");
      observer.disconnect();
      clearTimeout(slow);
      window.removeEventListener("offline", offline);
      window.removeEventListener("error", failed, true);
      window.removeEventListener("unhandledrejection", failed);
    }));
  }
  const offline = () => problem("You appear to be offline. Reconnect, then try again.");
  const failed = () => problem("We couldn't open the workspace. Check your connection, then try again.");
  const observer = new MutationObserver(ready);
  observer.observe(portal, { childList: true, subtree: true, attributes: true });
  const slow = setTimeout(() => problem("This is taking longer than usual. You can keep waiting or try again."), 30000);
  retry.addEventListener("click", () => location.reload());
  window.addEventListener("offline", offline);
  window.addEventListener("error", failed, true);
  window.addEventListener("unhandledrejection", failed);
  if (!navigator.onLine) offline();
  ready();
})();
