(function () {
    const terminalElement = document.getElementById('terminal');
    const fitAddon = new FitAddon.FitAddon();
    const term = new Terminal({
        cursorBlink: true,
        fontFamily: 'monospace',
        fontSize: 14,
        scrollback: 10000,
        convertEol: false,
        theme: {
            background: '#000000',
            foreground: '#f2f2f2',
            cursor: '#f2f2f2',
            selectionBackground: '#5a5a5a'
        }
    });

    let ready = false;

    function postResize() {
        if (!window.DroidVMConsole || !ready) return;
        window.DroidVMConsole.onResize(term.cols, term.rows);
    }

    function isCellReady() {
        try {
            const core = term._core;
            if (!core || !core._renderService) return false;
            const cell = core._renderService.dimensions.css.cell;
            return cell.width > 0 && cell.height > 0;
        } catch (e) {
            return false;
        }
    }

    function fit() {
        try {
            const core = term._core;
            if (core && !core.viewport) {
                core.viewport = { scrollBarWidth: 0 };
            }
            fitAddon.fit();
            postResize();
        } catch (e) {
            console.error('fit error:', e && e.message);
        }
    }

    let pendingFit = null;
    let fitAttempts = 0;
    function scheduleFit(delay) {
        if (pendingFit) clearTimeout(pendingFit);
        pendingFit = setTimeout(function runFit() {
            pendingFit = null;
            if (!isCellReady()) {
                if (++fitAttempts < 60) {
                    pendingFit = setTimeout(runFit, 50);
                    return;
                }
                console.error('fit: cell never became ready, attempts=' + fitAttempts);
            }
            fitAttempts = 0;
            fit();
        }, delay || 0);
    }

    term.loadAddon(fitAddon);
    term.open(terminalElement);
    term.focus();
    ready = true;
    scheduleFit(0);
    if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(function () { scheduleFit(0); });
    }

    term.onData(function (data) {
        if (window.DroidVMConsole) {
            window.DroidVMConsole.onData(data);
        }
    });

    term.onResize(function (size) {
        if (window.DroidVMConsole) {
            window.DroidVMConsole.onResize(size.cols, size.rows);
        }
    });

    window.addEventListener('resize', function () {
        scheduleFit(50);
    });

    if (typeof ResizeObserver !== 'undefined') {
        new ResizeObserver(function () {
            scheduleFit(50);
        }).observe(terminalElement);
    }

    document.body.addEventListener('click', function () {
        term.focus();
    });

    const MIN_FONT = 8;
    const MAX_FONT = 32;

    function clampFont(size) {
        if (!isFinite(size)) return term.options.fontSize;
        size = Math.round(size);
        if (size < MIN_FONT) size = MIN_FONT;
        if (size > MAX_FONT) size = MAX_FONT;
        return size;
    }

    function setFont(size) {
        size = clampFont(size);
        if (size === term.options.fontSize) return false;
        term.options.fontSize = size;
        scheduleFit(0);
        return true;
    }

    function reportFont() {
        if (window.DroidVMConsole && window.DroidVMConsole.onFontSize) {
            window.DroidVMConsole.onFontSize(term.options.fontSize);
        }
    }
    let touchMode = null;
    let panAccumY = 0;
    let panLastY = 0;
    let pinchStartDist = 0;
    let pinchStartFont = 0;

    function distance(t0, t1) {
        const dx = t0.clientX - t1.clientX;
        const dy = t0.clientY - t1.clientY;
        return Math.hypot(dx, dy);
    }

    function cellHeight() {
        try {
            return term._core._renderService.dimensions.css.cell.height || 16;
        } catch (e) {
            return 16;
        }
    }

    terminalElement.addEventListener('touchstart', function (e) {
        if (e.touches.length === 2) {
            touchMode = 'pinch';
            pinchStartDist = distance(e.touches[0], e.touches[1]);
            pinchStartFont = term.options.fontSize;
            e.preventDefault();
        } else if (e.touches.length === 1) {
            touchMode = 'pan';
            panLastY = e.touches[0].clientY;
            panAccumY = 0;
        }
    }, { passive: false, capture: true });

    terminalElement.addEventListener('touchmove', function (e) {
        if (touchMode === 'pinch' && e.touches.length === 2) {
            const dist = distance(e.touches[0], e.touches[1]);
            if (pinchStartDist > 0) {
                setFont(pinchStartFont * (dist / pinchStartDist));
            }
            e.preventDefault();
        } else if (touchMode === 'pan' && e.touches.length === 1) {
            const y = e.touches[0].clientY;
            const dy = y - panLastY;
            panLastY = y;
            panAccumY += dy;
            const ch = cellHeight();
            if (ch > 0) {
                const lines = Math.trunc(panAccumY / ch);
                if (lines !== 0) {
                    term.scrollLines(-lines);
                    panAccumY -= lines * ch;
                }
            }
            e.preventDefault();
        }
    }, { passive: false, capture: true });

    function endTouch(e) {
        const wasPinch = touchMode === 'pinch';
        const pinchChanged = wasPinch && term.options.fontSize !== pinchStartFont;
        if (e.touches.length === 0) {
            if (pinchChanged) reportFont();
            touchMode = null;
            panAccumY = 0;
            pinchStartDist = 0;
        } else if (e.touches.length === 1 && wasPinch) {
            if (pinchChanged) reportFont();
            touchMode = 'pan';
            panLastY = e.touches[0].clientY;
            panAccumY = 0;
            pinchStartDist = 0;
        }
    }
    terminalElement.addEventListener('touchend', endTouch, { capture: true });
    terminalElement.addEventListener('touchcancel', endTouch, { capture: true });

    window.DroidVMTerminal = {
        write: function (data) {
            term.write(data);
        },
        input: function (data) {
            if (window.DroidVMConsole) {
                window.DroidVMConsole.onData(data);
            }
        },
        clear: function () {
            term.reset();
            term.clear();
        },
        focus: function () {
            term.focus();
        },
        fit: function () { scheduleFit(0); },
        setFontSize: function (size) {
            setFont(size);
        }
    };

    if (window.DroidVMConsole) {
        window.DroidVMConsole.onReady();
    }
})();
