(function () {
    const key = "webmail-theme";
    const root = document.documentElement;
    const btn = document.getElementById("themeToggle");

    function applyTheme(theme) {
        root.setAttribute("data-theme", theme);
    }

    const stored = localStorage.getItem(key);
    applyTheme(stored === "dark" ? "dark" : "light");

    if (btn) {
        btn.addEventListener("click", function () {
            const next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
            applyTheme(next);
            localStorage.setItem(key, next);
        });
    }
})();
