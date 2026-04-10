<script lang="ts">
    import { login, isLoggedIn } from "$lib/services/auth.store";
    import { goto } from "$app/navigation";
    import { onMount } from "svelte";
    import Logo from "$lib/components/Logo.svelte";
    import { kbd } from "$lib/actions/kbd";

    let username = $state("");
    let password = $state("");
    let isPasswordHidden = $state(true);
    let isLoading = $state(false);
    let errorMessage = $state("");

    const isFormValid = $derived(username.trim() !== "" && password.trim() !== "");

    onMount(() => {
        if ($isLoggedIn) {
            goto("/dashboard");
        }
    });

    async function handleSubmit(e: Event) {
        e.preventDefault();
        if (!isFormValid) return;

        isLoading = true;
        errorMessage = "";

        try {
            const success = await login(username, password);
            if (success) {
                goto("/dashboard");
            } else {
                errorMessage = "Invalid username or password";
            }
        } catch (error) {
            errorMessage = "An error occurred. Please try again.";
        } finally {
            isLoading = false;
        }
    }
</script>

<div class="login-container">
    <div class="login-card">
        <div class="card-header">
            <Logo size="large" />
        </div>
        
        <div class="card-content">
            <form onsubmit={handleSubmit}>
                <div class="form-field">
                    <label for="username">Username</label>
                    <div class="input-wrapper">
                        <input
                            id="username"
                            type="text"
                            bind:value={username}
                            autocomplete="username"
                            placeholder="Enter username"
                            use:kbd
                        />
                        <i class="bi bi-person input-icon"></i>
                    </div>
                </div>

                <div class="form-field">
                    <label for="password">Password</label>
                    <div class="input-wrapper">
                        <input
                            id="password"
                            type={isPasswordHidden ? "password" : "text"}
                            bind:value={password}
                            autocomplete="current-password"
                            placeholder="Enter password"
                            use:kbd
                        />
                        <button
                            type="button"
                            class="visibility-toggle"
                            onclick={() => (isPasswordHidden = !isPasswordHidden)}
                        >
                            <i class="bi bi-eye{isPasswordHidden ? '-slash' : ''}"></i>
                        </button>
                    </div>
                </div>

                {#if errorMessage}
                    <div class="error-message">
                        {errorMessage}
                    </div>
                {/if}

                <button
                    type="submit"
                    class="login-button"
                    disabled={!isFormValid || isLoading}
                >
                    {isLoading ? "Logging in..." : "Login"}
                </button>
            </form>
        </div>
    </div>
</div>

<style>
    .login-container {
        display: flex;
        justify-content: center;
        align-items: center;
        height: 100vh;
        background-color: #f0f2f5;
    }

    .login-card {
        width: 100%;
        max-width: 400px;
        background: white;
        border-radius: 16px;
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
        overflow: hidden;
    }

    .card-header {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 32px 16px 24px;
        background: white;
    }

    .card-content {
        padding: 0 32px 32px;
    }

    form {
        display: flex;
        flex-direction: column;
        gap: 20px;
    }

    .form-field {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    .form-field label {
        font-size: 0.875rem;
        font-weight: 500;
        color: #424242;
    }

    .input-wrapper {
        position: relative;
        display: flex;
        align-items: center;
    }

    .input-wrapper input {
        width: 100%;
        padding: 14px 40px 14px 14px;
        border: 1px solid #d0d0d0;
        border-radius: 8px;
        font-size: 1rem;
        transition: all 0.2s;
        outline: none;
    }

    .input-wrapper input:focus {
        border-color: var(--primary-color);
        box-shadow: 0 0 0 2px rgba(1, 37, 150, 0.1);
    }

    .input-icon {
        position: absolute;
        right: 14px;
        color: #757575;
        font-size: 1.25rem;
        pointer-events: none;
    }

    .visibility-toggle {
        position: absolute;
        right: 8px;
        background: none;
        border: none;
        color: #757575;
        cursor: pointer;
        padding: 6px;
        display: flex;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        transition: background-color 0.2s;
    }

    .visibility-toggle:hover {
        background-color: #f5f5f5;
    }

    .visibility-toggle i {
        font-size: 1.25rem;
    }

    .error-message {
        padding: 12px;
        background-color: #ffebee;
        color: #c62828;
        border-radius: 8px;
        font-size: 0.875rem;
        text-align: center;
    }

    .login-button {
        width: 100%;
        padding: 14px;
        background-color: var(--primary-color);
        color: white;
        border: none;
        border-radius: 8px;
        font-size: 1rem;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
    }

    .login-button:hover:not(:disabled) {
        background-color: #013bb5;
        box-shadow: 0 4px 8px rgba(0, 0, 0, 0.3);
    }

    .login-button:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }
</style>
