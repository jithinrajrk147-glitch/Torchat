(function () {
  const B = window.BlackstringsBridge || {
    getIdentity: () => JSON.stringify({
      onionId: "blackstringsdemo7x3q2v9k4m8n6p2r5t7w9y3z4a6c8e2g4j6m8p2q.onion",
      pubKey: "demoPubKey",
      mailboxEnabled: false,
      defaultRelay: "",
      mailboxCount: 0,
      torStatus: "SYNCING",
      pendingCount: 0
    }),
    generateQrSvg: () => '<svg width="160" height="160" viewBox="0 0 160 160"><rect width="160" height="160" fill="#0d1117"/><rect x="20" y="20" width="40" height="40" fill="#00c97e"/><rect x="100" y="20" width="40" height="40" fill="#00c97e"/><rect x="20" y="100" width="40" height="40" fill="#00c97e"/></svg>',
    getFriends: () => "[]",
    addFriend: () => JSON.stringify({ ok: true }),
    getMessages: () => "[]",
    sendMessage: () => JSON.stringify({ ok: true, id: String(Date.now()) }),
    forwardMessage: () => JSON.stringify({ ok: true }),
    deleteMessage: () => {},
    setMailboxMode: () => {},
    setDefaultRelay: () => {},
    triggerSyncNow: () => {},
    copyText: () => {},
    pickMedia: () => {},
    scanQrCode: () => {},
    startVoiceRecord: () => true,
    stopVoiceRecord: () => JSON.stringify({ ok: false })
  };

  let state = {
    identity: null,
    friends: [],
    activePeer: null,
    messages: [],
    replyTo: null,
    isRecording: false,
    recInterval: null,
    recSeconds: 0,
    ctxMsg: null
  };

  const $ = (id) => document.getElementById(id);

  function init() {
    refreshIdentity();
    refreshFriends();

    if (!localStorage.getItem("bs_onboarded")) {
      openIdentityModal();
      localStorage.setItem("bs_onboarded", "1");
    }

    $("search-input").addEventListener("input", renderFriends);
    $("btn-tor-status").addEventListener("click", () => B.triggerSyncNow());
    $("btn-chat-sync").addEventListener("click", () => B.triggerSyncNow());
    $("btn-identity").addEventListener("click", openIdentityModal);
    $("btn-settings").addEventListener("click", openSettingsModal);
    $("btn-add-friend").addEventListener("click", () => $("modal-add-friend").classList.remove("hidden"));
    $("btn-onboarding-add").addEventListener("click", () => {
      $("modal-identity").classList.add("hidden");
      $("modal-add-friend").classList.remove("hidden");
    });

    document.querySelectorAll(".close-modal").forEach((btn) => {
      btn.addEventListener("click", () => $(btn.dataset.close).classList.add("hidden"));
    });

    $("btn-copy-onion").addEventListener("click", () => {
      if (state.identity) B.copyText(state.identity.onionId);
      $("btn-copy-onion").textContent = "Copied";
      setTimeout(() => ($("btn-copy-onion").textContent = "Copy Onion ID"), 1400);
    });

    $("btn-scan-qr").addEventListener("click", () => B.scanQrCode());
    $("btn-save-friend").addEventListener("click", saveFriend);
    $("btn-save-settings").addEventListener("click", saveSettings);
    $("btn-back").addEventListener("click", closeChat);

    $("btn-media").addEventListener("click", (e) => {
      e.stopPropagation();
      $("media-menu").classList.toggle("hidden");
    });

    document.querySelectorAll(".media-opt").forEach((btn) => {
      btn.addEventListener("click", () => {
        $("media-menu").classList.add("hidden");
        B.pickMedia(btn.dataset.kind);
      });
    });

    $("btn-mic").addEventListener("click", toggleVoiceRecord);
    $("btn-send").addEventListener("click", sendTextMessage);
    $("msg-input").addEventListener("keydown", (e) => {
      if (e.key === "Enter") sendTextMessage();
    });
    $("btn-cancel-reply").addEventListener("click", clearReply);

    document.addEventListener("click", (e) => {
      if (!e.target.closest("#media-menu") && !e.target.closest("#btn-media")) {
        $("media-menu").classList.add("hidden");
      }
      if (!e.target.closest("#context-menu")) {
        $("context-menu").classList.add("hidden");
      }
    });

    document.querySelectorAll("#context-menu button").forEach((btn) => {
      btn.addEventListener("click", () => handleContextAction(btn.dataset.action));
    });
  }

  function refreshIdentity() {
    state.identity = JSON.parse(B.getIdentity());
    const st = state.identity.torStatus || "BOOTSTRAPPING";
    $("tor-status-text").textContent = st;
    const pill = $("btn-tor-status");
    pill.classList.remove("sleeping", "bootstrapping");
    if (st === "SLEEPING") pill.classList.add("sleeping");
    if (st === "BOOTSTRAPPING") pill.classList.add("bootstrapping");

    if (state.identity.mailboxEnabled) {
      $("mailbox-banner").classList.remove("hidden");
      $("mailbox-count").textContent = state.identity.mailboxCount || 0;
    } else {
      $("mailbox-banner").classList.add("hidden");
    }
  }

  function refreshFriends() {
    state.friends = JSON.parse(B.getFriends());
    renderFriends();
  }

  function renderFriends() {
    const q = ($("search-input").value || "").trim().toLowerCase();
    const list = $("chat-list");
    list.innerHTML = "";
    const filtered = state.friends.filter(
      (f) => f.name.toLowerCase().includes(q) || f.onionId.toLowerCase().includes(q)
    );
    if (filtered.length === 0) {
      list.innerHTML = '<div style="text-align:center;color:#8b949e;padding:48px 20px;font-size:13px;">No peers yet. Tap + to add a friend by .onion or QR.</div>';
      return;
    }
    filtered.forEach((f) => {
      const el = document.createElement("div");
      el.className = "chat-item";
      const initials = (f.name || f.onionId).slice(0, 2).toUpperCase();
      const preview = f.lastType === "text" ? (f.lastText || "Tap to chat") : `[${f.lastType}]`;
      const timeStr = f.lastTs ? new Date(f.lastTs).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "";
      el.innerHTML = `
        <div class="avatar">${escapeHtml(initials)}</div>
        <div class="chat-meta">
          <div class="chat-row-top">
            <span class="chat-name">${escapeHtml(f.name)}</span>
            <span class="chat-time">${timeStr}</span>
          </div>
          <div class="chat-preview">${escapeHtml(preview)}</div>
        </div>
      `;
      el.addEventListener("click", () => openChat(f));
      list.appendChild(el);
    });
  }

  function openIdentityModal() {
    refreshIdentity();
    $("my-onion-display").textContent = state.identity.onionId;
    $("my-qr").innerHTML = B.generateQrSvg(state.identity.onionId);
    $("modal-identity").classList.remove("hidden");
  }

  function openSettingsModal() {
    refreshIdentity();
    $("toggle-mailbox").checked = !!state.identity.mailboxEnabled;
    $("input-default-relay").value = state.identity.defaultRelay || "";
    $("stat-pending").textContent = state.identity.pendingCount || 0;
    $("stat-mailbox").textContent = state.identity.mailboxCount || 0;
    $("modal-settings").classList.remove("hidden");
  }

  function saveFriend() {
    const name = $("input-friend-name").value.trim();
    const onion = $("input-friend-onion").value.trim().toLowerCase();
    const mailbox = $("input-friend-mailbox").value.trim().toLowerCase();
    const res = JSON.parse(B.addFriend(onion, name || onion.slice(0, 10), "", mailbox));
    if (!res.ok) {
      alert(res.error || "Invalid .onion address");
      return;
    }
    $("input-friend-name").value = "";
    $("input-friend-onion").value = "";
    $("input-friend-mailbox").value = "";
    $("modal-add-friend").classList.add("hidden");
    refreshFriends();
  }

  function saveSettings() {
    B.setMailboxMode($("toggle-mailbox").checked);
    B.setDefaultRelay($("input-default-relay").value.trim());
    B.triggerSyncNow();
    $("modal-settings").classList.add("hidden");
    refreshIdentity();
  }

  function openChat(friend) {
    state.activePeer = friend;
    $("chat-peer-name").textContent = friend.name;
    $("chat-peer-onion").textContent = friend.onionId;
    $("view-list").classList.remove("active");
    $("view-chat").classList.add("active");
    loadMessages();
  }

  function closeChat() {
    state.activePeer = null;
    clearReply();
    $("view-chat").classList.remove("active");
    $("view-list").classList.add("active");
    refreshFriends();
  }

  function loadMessages() {
    if (!state.activePeer) return;
    state.messages = JSON.parse(B.getMessages(state.activePeer.onionId));
    renderMessages();
  }

  function renderMessages() {
    const box = $("messages-container");
    box.innerHTML = "";
    state.messages.forEach((m) => {
      const row = document.createElement("div");
      row.className = `msg-row ${m.direction}`;
      row.dataset.id = m.id;

      let bodyHtml = "";
      if (m.replyPreview) {
        bodyHtml += `<div class="msg-quote">${escapeHtml(m.replyPreview)}</div>`;
      }
      if (m.type === "image" && m.mediaDataUrl) {
        bodyHtml += `<img class="msg-media-img" src="${m.mediaDataUrl}" alt="image" />`;
      } else if (m.type === "video" && m.mediaDataUrl) {
        bodyHtml += `<video class="msg-media-video" controls src="${m.mediaDataUrl}"></video>`;
      } else if (m.type === "file") {
        bodyHtml += `<a class="msg-file-card" href="${m.mediaDataUrl || '#'}" download="${escapeHtml(m.mediaName || 'file')}">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
          <span>${escapeHtml(m.mediaName || 'Encrypted File')}</span>
        </a>`;
      } else if (m.type === "voice") {
        bodyHtml += `<div class="voice-bubble">
          <button class="voice-play-btn" data-src="${m.mediaDataUrl || ''}">
            <svg viewBox="0 0 24 24" fill="currentColor"><polygon points="6 4 20 12 6 20 6 4"/></svg>
          </button>
          <svg class="voice-wave" viewBox="0 0 120 24" fill="none" stroke="#00c97e" stroke-width="2.2">
            <path d="M2 12v0M8 8v8M14 5v14M20 9v6M26 4v16M32 7v10M38 10v4M44 6v12M50 3v18M56 8v8M62 5v14M68 9v6M74 6v12M80 4v16M86 8v8M92 10v4M98 6v12M104 9v6M110 7v10M116 11v2" stroke-linecap="round"/>
          </svg>
          <span class="voice-dur">${m.mediaDuration || 1}s</span>
        </div>`;
      }
      if (m.text) {
        bodyHtml += `<div>${escapeHtml(m.text)}</div>`;
      }

      const time = new Date(m.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
      const statusSvg = m.direction === "out" ? getStatusSvg(m.status) : "";

      row.innerHTML = `
        <div class="msg-bubble">${bodyHtml}</div>
        <div class="msg-footer"><span>${time}</span>${statusSvg}</div>
      `;

      attachMessageGestures(row, m);
      box.appendChild(row);
    });

    box.querySelectorAll(".voice-play-btn").forEach((btn) => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        playVoiceBubble(btn);
      });
    });

    box.scrollTop = box.scrollHeight;
  }

  function playVoiceBubble(btn) {
    const src = btn.dataset.src;
    if (!src) return;
    if (btn._audio && !btn._audio.paused) {
      btn._audio.pause();
      btn.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><polygon points="6 4 20 12 6 20 6 4"/></svg>';
      return;
    }
    const audio = new Audio(src);
    btn._audio = audio;
    btn.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>';
    audio.onended = () => {
      btn.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><polygon points="6 4 20 12 6 20 6 4"/></svg>';
    };
    audio.play();
  }

  function attachMessageGestures(el, msg) {
    let pressTimer = null;
    let startX = 0;
    let startY = 0;
    let swiped = false;

    const start = (x, y) => {
      startX = x;
      startY = y;
      swiped = false;
      pressTimer = setTimeout(() => {
        showContextMenu(msg, x, y);
      }, 500);
    };

    const move = (x, y) => {
      const dx = x - startX;
      const dy = Math.abs(y - startY);
      if (Math.abs(dx) > 10 || dy > 10) clearTimeout(pressTimer);
      if (dx > 12 && dy < 30) {
        const clamped = Math.min(dx, 68);
        el.style.transform = `translateX(${clamped}px)`;
        if (clamped >= 52) swiped = true;
      }
    };

    const end = () => {
      clearTimeout(pressTimer);
      el.style.transform = "";
      if (swiped) setReply(msg);
    };

    el.addEventListener("touchstart", (e) => start(e.touches[0].clientX, e.touches[0].clientY), { passive: true });
    el.addEventListener("touchmove", (e) => move(e.touches[0].clientX, e.touches[0].clientY), { passive: true });
    el.addEventListener("touchend", end);
    el.addEventListener("mousedown", (e) => start(e.clientX, e.clientY));
    el.addEventListener("mouseup", end);
  }

  function showContextMenu(msg, x, y) {
    state.ctxMsg = msg;
    const menu = $("context-menu");
    menu.classList.remove("hidden");
    const mw = menu.offsetWidth || 156;
    const mh = menu.offsetHeight || 164;
    const vw = window.innerWidth;
    const vh = window.innerHeight;

    const left = Math.max(10, Math.min(x, vw - mw - 10));
    const top = y + mh + 12 > vh ? Math.max(10, y - mh - 8) : y + 8;
    menu.style.left = `${left}px`;
    menu.style.top = `${top}px`;
  }

  function handleContextAction(action) {
    const msg = state.ctxMsg;
    $("context-menu").classList.add("hidden");
    if (!msg) return;

    if (action === "copy") {
      B.copyText(msg.text || msg.mediaName || "");
    } else if (action === "reply") {
      setReply(msg);
    } else if (action === "delete") {
      B.deleteMessage(msg.id);
      loadMessages();
    } else if (action === "forward") {
      openForwardModal(msg);
    }
  }

  function openForwardModal(msg) {
    const list = $("forward-list");
    list.innerHTML = "";
    state.friends.forEach((f) => {
      const item = document.createElement("div");
      item.className = "forward-item";
      item.textContent = f.name;
      item.addEventListener("click", () => {
        B.forwardMessage(f.onionId, msg.id);
        $("modal-forward").classList.add("hidden");
      });
      list.appendChild(item);
    });
    $("modal-forward").classList.remove("hidden");
  }

  function setReply(msg) {
    state.replyTo = {
      id: msg.id,
      preview: msg.text || `[${msg.type}] ${msg.mediaName || ""}`
    };
    $("reply-bar-text").textContent = state.replyTo.preview;
    $("reply-bar").classList.remove("hidden");
    $("msg-input").focus();
  }

  function clearReply() {
    state.replyTo = null;
    $("reply-bar").classList.add("hidden");
  }

  function sendTextMessage() {
    if (!state.activePeer) return;
    const text = $("msg-input").value.trim();
    if (!text) return;
    $("msg-input").value = "";
    const rId = state.replyTo ? state.replyTo.id : "";
    const rPrev = state.replyTo ? state.replyTo.preview : "";
    clearReply();
    B.sendMessage(state.activePeer.onionId, "text", text, "", "", 0, rId, rPrev);
    loadMessages();
  }

  function toggleVoiceRecord() {
    if (!state.activePeer) return;
    if (!state.isRecording) {
      if (!B.startVoiceRecord()) return;
      state.isRecording = true;
      state.recSeconds = 0;
      $("icon-mic").classList.add("hidden");
      $("icon-stop").classList.remove("hidden");
      $("rec-timer").classList.remove("hidden");
      $("rec-timer").textContent = "0:00";
      state.recInterval = setInterval(() => {
        state.recSeconds += 1;
        const m = Math.floor(state.recSeconds / 60);
        const s = String(state.recSeconds % 60).padStart(2, "0");
        $("rec-timer").textContent = `${m}:${s}`;
      }, 1000);
    } else {
      clearInterval(state.recInterval);
      state.isRecording = false;
      $("icon-stop").classList.add("hidden");
      $("icon-mic").classList.remove("hidden");
      $("rec-timer").classList.add("hidden");
      const res = JSON.parse(B.stopVoiceRecord());
      if (res.ok) {
        const rId = state.replyTo ? state.replyTo.id : "";
        const rPrev = state.replyTo ? state.replyTo.preview : "";
        clearReply();
        B.sendMessage(state.activePeer.onionId, "voice", "", res.path, res.name, res.duration, rId, rPrev);
        loadMessages();
      }
    }
  }

  function getStatusSvg(status) {
    if (status === "DELIVERED") {
      return '<svg class="msg-status-icon delivered" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><polyline points="18 6 7 17 2 12"/><polyline points="22 10 11 21 9 19"/></svg>';
    }
    if (status === "SENT") {
      return '<svg class="msg-status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><polyline points="20 6 9 17 4 12"/></svg>';
    }
    return '<svg class="msg-status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15 15"/></svg>';
  }

  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // Native -> JS callbacks
  window.onMediaPicked = function (jsonStr) {
    if (!state.activePeer) return;
    const d = JSON.parse(jsonStr);
    const rId = state.replyTo ? state.replyTo.id : "";
    const rPrev = state.replyTo ? state.replyTo.preview : "";
    clearReply();
    B.sendMessage(state.activePeer.onionId, d.type, "", d.path, d.name, 0, rId, rPrev);
    loadMessages();
  };

  window.onQrScanned = function (jsonStr) {
    const d = JSON.parse(jsonStr);
    $("input-friend-onion").value = d.value || "";
  };

  window.onNativeEvent = function () {
    refreshIdentity();
    if (state.activePeer) loadMessages();
    else refreshFriends();
  };

  window.handleAndroidBack = function () {
    if (state.activePeer) {
      closeChat();
      return true;
    }
    return false;
  };

  document.addEventListener("DOMContentLoaded", init);
})();
