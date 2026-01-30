/**
 * Play Interface - WebSocket chat for solo play
 * Handles streaming communication with `/ws/play/{gameId}`
 */

class PlayInterface {
    constructor() {
        this.messagesContainer = document.getElementById('chat-messages');
        this.messageInput = document.getElementById('message-input');
        this.sendButton = document.getElementById('send-btn');

        // Server-assigned session id (populated from the initial "session" message)
        this.sessionId = null;
        // Game id from the page (set via data attribute from server)
        this.gameId = this.getGameIdFromPage();

        // WebSocket state
        this.ws = null;
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.reconnectDelay = 1000; // Start with 1 second

        // Message tracking
        this.currentAssistantMessage = null;
        this.currentMessageId = null;
        this.streamingBuffers = new Map();
        this.historyLoaded = false;

        // Round-trip state from server, keyed by slot (e.g., "actor_draft", "pending_roll")
        this.stashBySlot = new Map();

        // Draft panel elements
        this.draftPanel = document.getElementById('draft-panel');
        this.draftContent = document.getElementById('draft-content');
        this.draftCloseBtn = document.getElementById('draft-close');

        this.setupEventListeners();
        this.setInputEnabled(false);
        this.connect();
    }

    setupEventListeners() {
        this.sendButton.addEventListener('click', () => this.sendMessage());

        // Auto-resize textarea
        this.messageInput.addEventListener('input', () => this.autoResize());

        // Click-to-send for server-rendered choice buttons
        this.messagesContainer.addEventListener('click', (e) => {
            const btn = e.target?.closest?.('button[data-choice]');
            if (!btn) return;

            const choice = btn.getAttribute('data-choice') || btn.textContent;
            const trimmed = (choice || '').trim();
            if (!trimmed) return;

            // Disable all buttons in this choice block to avoid double-submits
            const block = btn.closest('.player-choices');
            if (block) {
                block.querySelectorAll('button[data-choice]').forEach(b => b.disabled = true);
            } else {
                btn.disabled = true;
            }

            this.sendMessage(trimmed);
        });

        // Handle Enter key (Shift+Enter for new line)
        this.messageInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });

        // Draft panel close button
        if (this.draftCloseBtn) {
            this.draftCloseBtn.addEventListener('click', () => this.hideDraftPanel());
        }
    }

    // ===== WebSocket Connection =====

    connect() {
        if (!this.gameId) {
            console.error('Cannot connect: no game ID');
            this.updateConnectionStatus('error');
            this.addSystemMessage('Error: No game ID found. Please return to the games list.');
            return;
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws/play/${encodeURIComponent(this.gameId)}`;

        console.log('Connecting to WebSocket:', wsUrl);
        this.updateConnectionStatus('connecting');

        this.ws = new WebSocket(wsUrl);

        this.ws.onopen = () => {
            console.log('WebSocket connected');
            this.reconnectAttempts = 0;
            this.reconnectDelay = 1000;
            this.updateConnectionStatus('connected');

            // Request conversation history
            this.requestHistory();
        };

        this.ws.onmessage = (event) => {
            try {
                const message = JSON.parse(event.data);
                this.handleServerMessage(message);
            } catch (e) {
                console.error('Error parsing WebSocket message:', e);
            }
        };

        this.ws.onclose = (event) => {
            console.log('WebSocket closed:', event.code, event.reason);
            this.updateConnectionStatus('disconnected');
            this.setInputEnabled(false);

            // Attempt reconnect unless intentionally closed
            if (event.code !== 1000) {
                this.attemptReconnect();
            }
        };

        this.ws.onerror = (error) => {
            console.error('WebSocket error:', error);
            this.updateConnectionStatus('error');
        };
    }

    attemptReconnect() {
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error('Max reconnect attempts reached');
            this.addSystemMessage('Connection lost. Please refresh the page.');
            return;
        }

        this.reconnectAttempts++;
        const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1);

        console.log(`Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
        this.updateConnectionStatus('reconnecting');

        setTimeout(() => this.connect(), delay);
    }

    updateConnectionStatus(status) {
        // Update connection indicator if present
        const indicator = document.getElementById('connection-status');
        if (indicator) {
            indicator.className = `connection-status ${status}`;
            indicator.title = status.charAt(0).toUpperCase() + status.slice(1);
        }
    }

    // ===== Message Handling =====

    handleServerMessage(message) {
        const type = message.type;

        switch (type) {
            case 'session':
                console.log('Session established:', message.gameId);
                this.sessionId = message.sessionId;
                if (message.gameId && message.gameId !== this.gameId) {
                    this.gameId = message.gameId;
                    localStorage.setItem('soloplay.gameId', this.gameId);
                }
                this.setInputEnabled(true);
                this.messageInput.focus();
                break;

            case 'history':
                this.handleHistory(message.messages);
                break;

            case 'user_echo':
                this.handleUserEcho(message.senderSessionId, message.text);
                break;

            case 'assistant_start':
                this.handleAssistantStart(message.id);
                break;

            case 'assistant_delta':
                this.handleAssistantDelta(message.id, message.text);
                break;

            case 'assistant_done':
                this.handleAssistantDone(message.id, message.markdown, message.html);
                break;

            case 'html_fragment':
                this.handleHtmlFragment(message.id, message.message);
                break;

            case 'stateful_update':
                this.handleStatefulUpdate(message.slot, message.html, message.stash);
                break;

            case 'error':
                this.handleError(message.id, message.message);
                break;

            default:
                console.warn('Unknown message type:', type);
        }
    }

    getGameIdFromPage() {
        const chatArea = document.querySelector('.chat-area[data-game-id]');
        if (chatArea && chatArea.dataset.gameId) {
            console.log('Game ID from data attribute:', chatArea.dataset.gameId);
            return chatArea.dataset.gameId;
        }
        // Fallback: extract from URL path /play/{gameId}
        const match = window.location.pathname.match(/\/play\/([^/]+)/);
        if (match) {
            const gameId = decodeURIComponent(match[1]);
            console.log('Game ID from URL:', gameId);
            return gameId;
        }
        console.error('No game ID found in page or URL');
        return null;
    }

    requestHistory() {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({
                type: 'history_request',
                limit: 50
            }));
        }
    }

    handleHistory(messages) {
        console.log('Received history:', messages?.length, 'messages');

        // Only apply history once, and never wipe already-rendered real-time messages.
        // History can arrive after other broadcast messages (e.g., when another tab is generating).
        if (this.historyLoaded || this.messagesContainer.childElementCount > 0) {
            this.historyLoaded = true;
            return;
        }
        this.historyLoaded = true;

        // Clear existing messages
        this.messagesContainer.innerHTML = '';

        if (!messages || messages.length === 0) {
            // Fresh start - show appropriate message
            this.sendMessage('/start');
            return;
        }

        // Restore messages from history
        messages.forEach(msg => {
            if (msg.role === 'user') {
                this.addUserMessage(msg.markdown);
            } else {
                // Use HTML if available, otherwise markdown
                this.addAssistantMessage({ html: msg.html, markdown: msg.markdown });
            }
        });

        this.scrollToBottom();
    }

    /**
     * Handle user_echo - broadcast of user message from server.
     * This is how all tabs (including the sender) receive user messages.
     * Avoids duplicate display by checking senderSessionId.
     */
    handleUserEcho(senderSessionId, text) {
        if (this.sessionId && senderSessionId === this.sessionId) {
            return;
        }

        // Message from another tab/user - display it
        console.debug('User echo from another connection:', text);
        this.addUserMessage(text);

        // Disable input since generation is starting from another tab
        this.setInputEnabled(false);
    }

    handleAssistantStart(messageId) {
        console.debug('Assistant starting:', messageId);
        this.currentMessageId = messageId;

        if (!this.streamingBuffers.has(messageId)) {
            this.streamingBuffers.set(messageId, '');
        }
        this.currentAssistantMessage = this.ensureStreamingMessage(messageId);
        this.scrollToBottom();
    }

    handleAssistantDelta(messageId, text) {
        const msgDiv = this.ensureStreamingMessage(messageId);
        const streamingContent = msgDiv.querySelector('.streaming-content');
        if (!streamingContent) {
            return;
        }

        const delta = text ?? '';
        const current = this.streamingBuffers.get(messageId) ?? '';
        const updated = current + delta;
        this.streamingBuffers.set(messageId, updated);
        streamingContent.textContent = updated;
        this.scrollToBottom();
    }

    handleAssistantDone(messageId, markdown, html) {
        console.debug('Assistant done:', messageId);

        const msgDiv = document.getElementById(`msg-${messageId}`) || this.ensureStreamingMessage(messageId);
        const bufferedMarkdown = this.streamingBuffers.get(messageId);
        const resolvedMarkdown =
            typeof markdown === 'string' && markdown.length > 0
                ? markdown
                : (typeof bufferedMarkdown === 'string' ? bufferedMarkdown : markdown);
        this.setAssistantMessageContent(msgDiv, { html, markdown: resolvedMarkdown });
        msgDiv.classList.remove('streaming');
        this.streamingBuffers.delete(messageId);

        // Reset state
        if (messageId === this.currentMessageId) {
            this.currentAssistantMessage = null;
            this.currentMessageId = null;
        }

        // Re-enable input
        this.setInputEnabled(true);
        this.messageInput.focus();
        this.scrollToBottom();
    }

    setAssistantMessageContent(msgDiv, content) {
        msgDiv.replaceChildren();

        const { html, markdown } =
            content && typeof content === 'object'
                ? content
                : { html: content, markdown: undefined };

        const htmlContent = typeof html === 'string' ? html.trim() : '';
        if (htmlContent) {
            msgDiv.innerHTML = htmlContent;
            return;
        }

        const markdownContent = typeof markdown === 'string' ? markdown : '';
        if (markdownContent) {
            const pre = document.createElement('pre');
            const code = document.createElement('code');
            code.textContent = markdownContent;
            pre.appendChild(code);
            msgDiv.appendChild(pre);
            return;
        }
    }

    ensureStreamingMessage(messageId) {
        const existing = document.getElementById(`msg-${messageId}`);
        if (existing) {
            existing.classList.add('streaming');
            if (!existing.querySelector('.streaming-content')) {
                const streamingContent = document.createElement('div');
                streamingContent.className = 'streaming-content';
                existing.appendChild(streamingContent);
            }
            this.currentAssistantMessage = existing;
            this.currentMessageId = messageId;
            return existing;
        }

        const msgDiv = document.createElement('div');
        msgDiv.className = 'message assistant streaming';
        msgDiv.id = `msg-${messageId}`;

        const streamingContent = document.createElement('div');
        streamingContent.className = 'streaming-content';
        msgDiv.appendChild(streamingContent);

        this.messagesContainer.appendChild(msgDiv);
        this.currentAssistantMessage = msgDiv;
        this.currentMessageId = messageId;
        return msgDiv;
    }

    handleHtmlFragment(id, html) {
        console.debug('Fragment message', id, html);
        const msgDiv = document.createElement('div');
        msgDiv.className = 'message-effect';
        msgDiv.dataset['id'] = id;
        msgDiv.innerHTML = html;
        this.messagesContainer.appendChild(msgDiv);
        this.scrollToBottom();
        return msgDiv;
    }

    handleStatefulUpdate(slot, html, stash) {
        // Store or clear stash by slot for round-tripping with next message
        if (stash) {
            this.stashBySlot.set(slot, stash);
            console.debug('Stash updated:', slot, stash['@type']);
        } else {
            this.stashBySlot.delete(slot);
            console.debug('Stash cleared:', slot);
        }

        // Update UI based on slot type
        if (slot === 'actor_draft') {
            if (stash) {
                this.renderDraftPanel(stash, html);
                this.showDraftPanel();
            } else {
                this.hideDraftPanel();
            }
        } else if (slot === 'pending_roll') {
            // Pending roll is displayed as an HTML fragment in the chat
            if (html) {
                this.handleHtmlFragment(slot, html);
            }
        }
    }

    /**
     * Get the current stash to send back to the server.
     * If there's only one stash, return it directly.
     * If multiple, return the most recent one (for now).
     */
    getCurrentStash() {
        if (this.stashBySlot.size === 0) return null;
        if (this.stashBySlot.size === 1) {
            return this.stashBySlot.values().next().value;
        }
        // Multiple stashes - prioritize pending_roll over actor_draft for gameplay
        if (this.stashBySlot.has('pending_roll')) {
            return this.stashBySlot.get('pending_roll');
        }
        return this.stashBySlot.values().next().value;
    }

    renderDraftPanel(draft, serverHtml) {
        if (!this.draftContent) return;

        // Use server-provided HTML/markdown if available
        if (serverHtml) {
            this.draftContent.innerHTML = serverHtml;
            return;
        }

        // Fallback to client-side rendering
        const type = draft['@type'];
        let html = '';

        if (type === 'actor_draft') {
            html = this.renderActorDraft(draft);
        } else {
            // Generic fallback for unknown draft types
            html = `<pre>${JSON.stringify(draft, null, 2)}</pre>`;
        }

        this.draftContent.innerHTML = html;
    }

    renderActorDraft(draft) {
        const fields = [];

        if (draft.name) {
            fields.push(`<div class="draft-field"><span class="draft-label">Name:</span> <span class="draft-value">${this.escapeHtml(draft.name)}</span></div>`);
        }
        if (draft.actorClass) {
            fields.push(`<div class="draft-field"><span class="draft-label">Class:</span> <span class="draft-value">${this.escapeHtml(draft.actorClass)}</span></div>`);
        }
        if (draft.level) {
            fields.push(`<div class="draft-field"><span class="draft-label">Level:</span> <span class="draft-value">${draft.level}</span></div>`);
        }
        if (draft.summary) {
            fields.push(`<div class="draft-field"><span class="draft-label">Summary:</span> <span class="draft-value">${this.escapeHtml(draft.summary)}</span></div>`);
        }
        if (draft.description) {
            fields.push(`<div class="draft-field draft-description"><span class="draft-label">Description:</span><div class="draft-value">${this.escapeHtml(draft.description)}</div></div>`);
        }
        if (draft.tags && draft.tags.length > 0) {
            const tagHtml = draft.tags.map(t => `<span class="draft-tag">${this.escapeHtml(t)}</span>`).join(' ');
            fields.push(`<div class="draft-field"><span class="draft-label">Tags:</span> <span class="draft-value">${tagHtml}</span></div>`);
        }

        if (fields.length === 0) {
            return '<p class="draft-empty">No character details yet. Start describing your character!</p>';
        }

        return `<div class="draft-actor">${fields.join('')}</div>`;
    }

    showDraftPanel() {
        if (this.draftPanel) {
            this.draftPanel.classList.remove('hidden');
        }
    }

    hideDraftPanel() {
        if (this.draftPanel) {
            this.draftPanel.classList.add('hidden');
        }
        if (this.draftContent) {
            this.draftContent.innerHTML = '';
        }
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    handleError(messageId, errorMessage) {
        console.error('Server error:', messageId, errorMessage);

        if (this.currentAssistantMessage) {
            this.currentAssistantMessage.innerHTML = `<p class="error">Error: ${errorMessage}</p>`;
            this.currentAssistantMessage.classList.remove('streaming');
            this.currentAssistantMessage = null;
            this.currentMessageId = null;
        } else {
            this.addSystemMessage(`Error: ${errorMessage}`);
        }

        this.setInputEnabled(true);
    }

    // ===== UI Methods =====

    autoResize() {
        this.messageInput.style.height = 'auto';
        this.messageInput.style.height = this.messageInput.scrollHeight + 'px';
    }

    sendMessage(message = '') {
        message = message || this.messageInput.value.trim();
        if (!message) return;

        if (!this.sessionId) {
            this.addSystemMessage('Connecting...');
            return;
        }

        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            this.addSystemMessage('Not connected. Attempting to reconnect...');
            this.connect();
            return;
        }

        // Disable input while processing
        this.setInputEnabled(false);

        // Add user message to chat immediately (optimistic UI)
        this.addUserMessage(message);

        // Clear input
        this.messageInput.value = '';
        this.autoResize();

        // Send to server (will broadcast to all connections including us)
        // Include current stash for round-trip state management
        this.ws.send(JSON.stringify({
            type: 'user_message',
            text: message,
            stash: this.getCurrentStash()
        }));
    }

    addUserMessage(text) {
        const msgDiv = document.createElement('div');
        msgDiv.className = 'message user';
        msgDiv.textContent = text;
        this.messagesContainer.appendChild(msgDiv);
        this.scrollToBottom();
        return msgDiv;
    }

    addAssistantMessage(content) {
        const msgDiv = document.createElement('div');
        msgDiv.className = 'message assistant';
        this.setAssistantMessageContent(msgDiv, content);
        this.messagesContainer.appendChild(msgDiv);
        this.scrollToBottom();
        return msgDiv;
    }

    addSystemMessage(text) {
        const msgDiv = document.createElement('div');
        msgDiv.className = 'message system';
        msgDiv.textContent = text;
        this.messagesContainer.appendChild(msgDiv);
        this.scrollToBottom();
        return msgDiv;
    }

    scrollToBottom() {
        this.messagesContainer.scrollTop = this.messagesContainer.scrollHeight;
    }

    setInputEnabled(enabled) {
        this.messageInput.disabled = !enabled;
        this.sendButton.disabled = !enabled;
    }
}

// Initialize when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        window.playInterface = new PlayInterface();
    });
} else {
    window.playInterface = new PlayInterface();
}
