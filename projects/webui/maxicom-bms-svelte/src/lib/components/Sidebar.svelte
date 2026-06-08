<script lang="ts">
  import { page } from '$app/stores';
  import Logo from './Logo.svelte';

  interface MenuItem {
    label: string;
    icon: string;
    route: string;
  }

  const menuItems: MenuItem[] = [
    { label: 'Dashboard', icon: 'speedometer2', route: '/dashboard' },
    { label: 'IEC104 Test', icon: 'diagram-3', route: '/iec104-test' },
    { label: 'Strings', icon: 'battery-charging', route: '/setting/strings' },
    { label: 'Serial', icon: 'usb-symbol', route: '/setting/serial' },
    { label: 'Network', icon: 'wifi', route: '/setting/network' },
    { label: 'Maintenance', icon: 'tools', route: '/setting/maintenance' },
    { label: 'Account', icon: 'person', route: '/setting/account' }
  ];

  const currentPath = $derived($page.url.pathname);
</script>

<div class="sidebar">
  <div class="sidebar-header">
    <Logo size="small" />
  </div>

  <nav class="menu-list">
    {#each menuItems as item, index}
      {#if index === 1}
        <div class="menu-section-label">SETTING</div>
      {/if}
      <a
        href={item.route}
        class="menu-item"
        class:active={currentPath === item.route || currentPath.startsWith(item.route + '/')}
      >
        <i class="bi bi-{item.icon} menu-icon"></i>
        <span class="menu-label">{item.label}</span>
      </a>
    {/each}
  </nav>
</div>

<style>
  .sidebar {
    width: var(--sidebar-width);
    height: 100%;
    background-color: #f5f5f5;
    display: flex;
    flex-direction: column;
    border-right: 1px solid #e0e0e0;
}

/* -------------------------------
   HEADER
-------------------------------- */
.sidebar-header {
    padding: 14px var(--sidebar-padding-x);
    border-bottom: 1px solid #e0e0e0;
    background-color: #ffffff;
    display: flex;
    justify-content: center;
    align-items: center;
}

/* -------------------------------
   MENU LIST
-------------------------------- */
.menu-list {
    flex: 1;
    padding: 6px 0;
    overflow-y: auto;
}

/* section label (SETTING) */
.menu-section-label {
    padding: 14px var(--sidebar-padding-x) 6px;
    font-size: 11px;
    font-weight: 600;
    color: #9ca3af;
    letter-spacing: 0.06em;
}

/* -------------------------------
   MENU ITEM
-------------------------------- */
.menu-item {
    display: flex;
    align-items: center;
    padding: 10px var(--sidebar-padding-x);
    gap: 12px;
    color: #374151;
    text-decoration: none;
    transition: background-color 0.15s ease, color 0.15s ease;
    border-left: 3px solid transparent;
}

.menu-item:hover {
    background-color: #eeeeee;
    color: var(--primary-color);
}

.menu-item.active {
    background-color: #e3f2fd;
    color: var(--primary-color);
    border-left-color: var(--primary-color);
    font-weight: 500;
}

/* -------------------------------
   ICON
-------------------------------- */
.menu-icon {
    font-size: var(--sidebar-icon-size);
    width: 20px;
    text-align: center;
    flex-shrink: 0;
}

/* -------------------------------
   LABEL
-------------------------------- */
.menu-label {
    font-size: var(--sidebar-font-size);
    line-height: 1.2;
    white-space: nowrap;
}
</style>
