// Global throttle to prevent rapid multiple executions across all inputs
let lastTriggerTime = 0;

export function kbd(node: HTMLInputElement) {
    const handler = (event: Event) => {
        const now = Date.now();
        // Prevent triggering the keyboard more than once every 1000ms globally
        if (now - lastTriggerTime < 1000) {
            return;
        }
        lastTriggerTime = now;
        
        const type = node.type === 'number' ? 'number' : 'string';
        
        fetch(`/rest/start_kbd?type=${type}`, {
            method: 'POST'
        }).catch(err => console.error('Failed to trigger keyboard script', err));
    };

    // Only listen to click (and touchstart) to avoid programmatic focus loops
    // that occur when the virtual keyboard steals and returns focus.
    node.addEventListener('click', handler);
    node.addEventListener('touchstart', handler, { passive: true });

    return {
        destroy() {
            node.removeEventListener('click', handler);
            node.removeEventListener('touchstart', handler);
        }
    };
}
