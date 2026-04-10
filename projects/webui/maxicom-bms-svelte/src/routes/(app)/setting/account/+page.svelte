<script lang="ts">
    import { showToast } from "$lib/services/toast.store";
    import { changePassword } from "$lib/services/auth.store";
    import { kbd } from "$lib/actions/kbd";

    let isOldHidden = $state(true);
    let isNewHidden = $state(true);
    let isConfirmHidden = $state(true);
    let isLoading = $state(false);

    let formData = $state({
        oldPassword: "",
        newPassword: "",
        confirmPassword: ""
    });

    let errors = $state({
        oldPassword: "",
        newPassword: "",
        confirmPassword: "",
        form: ""
    });

    function validateForm(): boolean {
        errors = {
            oldPassword: "",
            newPassword: "",
            confirmPassword: "",
            form: ""
        };

        if (!formData.oldPassword.trim()) {
            errors.oldPassword = "Old password is required";
        }

        if (!formData.newPassword.trim()) {
            errors.newPassword = "New password is required";
        }
        if (!formData.confirmPassword.trim()) {
            errors.confirmPassword = "Please confirm your password";
        } else if (formData.newPassword !== formData.confirmPassword) {
            errors.form = "Passwords do not match!";
        }

        return !errors.oldPassword && !errors.newPassword && !errors.confirmPassword && !errors.form;
    }

    async function handleSubmit(e: Event) {
        e.preventDefault();

        if (!validateForm()) {
            return;
        }

        isLoading = true;

        try {
            const success = await changePassword(
                formData.oldPassword,
                formData.newPassword
            );

            if (success) {
                formData = {
                    oldPassword: "",
                    newPassword: "",
                    confirmPassword: ""
                };
                errors = {
                    oldPassword: "",
                    newPassword: "",
                    confirmPassword: "",
                    form: ""
                };
            }
        } catch (err: any) {
            showToast(err.message || "Failed to change password", "error");
        } finally {
            isLoading = false;
        }
    }

    function togglePasswordVisibility(field: 'old' | 'new' | 'confirm') {
        if (field === 'old') {
            isOldHidden = !isOldHidden;
        } else if (field === 'new') {
            isNewHidden = !isNewHidden;
        } else {
            isConfirmHidden = !isConfirmHidden;
        }
    }
</script>

<div class="page-container">
    <div class="header-content">
        <h1 class="page-title">
            <i class="bi bi-person title-icon"></i>
            Account Settings
        </h1>
        <p class="page-subtitle">
            Change Administrator Password
        </p>
    </div>

    <div class="account-card">
        <div class="card-header">
            <h2>Change password</h2>
        </div>
        <div class="card-content">
            <form onsubmit={handleSubmit} class="account-form">
                <div class="form-field">
                    <label for="oldPassword">Old password</label>
                    <div class="input-wrapper">
                        <input
                            id="oldPassword"
                            type={isOldHidden ? 'password' : 'text'}
                            bind:value={formData.oldPassword}
                            placeholder="Enter your current password"
                            class:error={errors.oldPassword}
                            use:kbd
                        />
                        <button
                            type="button"
                            class="toggle-btn"
                            onclick={() => togglePasswordVisibility('old')}
                            title={isOldHidden ? 'Show password' : 'Hide password'}
                        >
                            <i class={`bi ${isOldHidden ? 'bi-eye-slash' : 'bi-eye'}`}></i>
                        </button>
                    </div>
                    {#if errors.oldPassword}
                        <span class="error-message">{errors.oldPassword}</span>
                    {/if}
                </div>

                <div class="form-field">
                    <label for="newPassword">New password</label>
                    <div class="input-wrapper">
                        <input
                            id="newPassword"
                            type={isNewHidden ? 'password' : 'text'}
                            bind:value={formData.newPassword}
                            placeholder="Enter your new password"
                            class:error={errors.newPassword}
                            use:kbd
                        />
                        <button
                            type="button"
                            class="toggle-btn"
                            onclick={() => togglePasswordVisibility('new')}
                            title={isNewHidden ? 'Show password' : 'Hide password'}
                        >
                            <i class={`bi ${isNewHidden ? 'bi-eye-slash' : 'bi-eye'}`}></i>
                        </button>
                    </div>
                    {#if errors.newPassword}
                        <span class="error-message">{errors.newPassword}</span>
                    {/if}
                </div>

                <div class="form-field">
                    <label for="confirmPassword">Confirm new password</label>
                    <div class="input-wrapper">
                        <input
                            id="confirmPassword"
                            type={isConfirmHidden ? 'password' : 'text'}
                            bind:value={formData.confirmPassword}
                            placeholder="Confirm your new password"
                            class:error={errors.confirmPassword || errors.form}
                            use:kbd
                        />
                        <button
                            type="button"
                            class="toggle-btn"
                            onclick={() => togglePasswordVisibility('confirm')}
                            title={isConfirmHidden ? 'Show password' : 'Hide password'}
                        >
                            <i class={`bi ${isConfirmHidden ? 'bi-eye-slash' : 'bi-eye'}`}></i>
                        </button>
                    </div>
                    {#if errors.confirmPassword}
                        <span class="error-message">{errors.confirmPassword}</span>
                    {/if}
                    {#if errors.form}
                        <span class="error-message">{errors.form}</span>
                    {/if}
                </div>

                <button
                    type="submit"
                    class="btn-primary save-button"
                    disabled={isLoading}
                >
                    {isLoading ? 'Saving...' : 'Save Changes'}
                </button>
            </form>
        </div>
    </div>
</div>

<style>
    .page-container {
        padding: 24px;
    }

    .header-content {
        padding-bottom: 12px;
        margin-bottom: 24px;
    }

    .page-title {
        margin: 0 0 8px 0;
        display: flex;
        align-items: center;
        gap: 12px;
        color: var(--primary-color);
        font-size: 1.8rem;
        font-weight: 500;
    }

    .title-icon {
        font-size: 2rem;
        color: var(--accent-color);
    }

    .page-subtitle {
        margin: 0;
        color: #666;
        font-size: 1rem;
        line-height: 1.5;
    }

    .account-card {
        max-width: 600px;
        background: white;
        border-radius: 12px;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
        overflow: hidden;
    }

    .card-header {
        padding: 20px 24px;
        border-bottom: 1px solid #eee;
        background-color: #fafafa;
    }

    .card-header h2 {
        margin: 0;
        font-size: 1.1rem;
        font-weight: 600;
        color: #333;
    }

    .card-content {
        padding: 24px;
    }

    .account-form {
        display: flex;
        flex-direction: column;
        gap: 20px;
    }

    .form-field {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    label {
        font-size: 0.875rem;
        font-weight: 500;
        color: #424242;
    }

    .input-wrapper {
        position: relative;
        display: flex;
        align-items: center;
    }

    input {
        width: 100%;
        padding: 12px 40px 12px 12px;
        border: 1px solid #e0e0e0;
        border-radius: 6px;
        font-size: 0.9375rem;
        transition: border-color 0.2s;
    }

    input:focus {
        outline: none;
        border-color: var(--primary-color);
    }

    input.error {
        border-color: var(--warn-color);
    }

    .toggle-btn {
        position: absolute;
        right: 12px;
        background: none;
        border: none;
        color: #666;
        cursor: pointer;
        padding: 4px;
        display: flex;
        align-items: center;
        justify-content: center;
        transition: color 0.2s;
    }

    .toggle-btn:hover {
        color: var(--primary-color);
    }

    .error-message {
        font-size: 0.75rem;
        color: var(--warn-color);
        margin-top: -4px;
    }

    .save-button {
        align-self: flex-start;
        padding: 12px 24px;
        background-color: var(--primary-color);
        color: white;
        border: none;
        border-radius: 6px;
        font-size: 0.9375rem;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s;
        margin-top: 8px;
    }

    .save-button:hover:not(:disabled) {
        background-color: #013bb5;
    }

    .save-button:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    .btn-primary {
        background-color: var(--primary-color);
        color: white;
    }

    .btn-primary:hover:not(:disabled) {
        background-color: #013bb5;
    }

    .btn-primary:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }
</style>
