<#-- =============================================================================
     EIGEN IAM — Page de connexion locale (par établissement)
     Template FreeMarker pour Keycloak Local — thème eigen-local
     ============================================================================= -->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=social.displayInfo displayWide=(realm.password && social.providers??)
                             bodyClass=(!realm.password && social.providers?? && !realm.registrationAllowed)?then('login-pf-page-wide', '');
                             section>
    <#if section = "header">
        <div class="eigen-local-header">
            <div class="eigen-local-logo">
                <div class="eigen-badge">E</div>
                <div class="eigen-local-identity">
                    <span class="eigen-local-title">EIGEN</span>
                    <span class="eigen-local-etab">${realm.displayName?replace("EIGEN — ", "")}</span>
                </div>
            </div>
            <div class="eigen-local-subtitle">Portail Académique Numérique</div>
        </div>
    <#elseif section = "form">
        <div id="kc-form">
            <#if realm.password>
                <form id="kc-form-login" onsubmit="login.disabled = true; return true;"
                      action="${url.loginAction}" method="post">

                    <#if !usernameHidden??>
                        <div class="${properties.kcFormGroupClass!}">
                            <label for="username" class="${properties.kcLabelClass!} eigen-label">
                                Identifiant ou email institutionnel
                            </label>
                            <div class="eigen-input-group">
                                <span class="eigen-input-icon">👤</span>
                                <input tabindex="1" id="username"
                                       class="${properties.kcInputClass!} eigen-input"
                                       name="username"
                                       value="${(login.username!'')?html}"
                                       type="text"
                                       placeholder="Ex: jean.dupont ou ETU-2025-00412"
                                       autofocus autocomplete="off"/>
                            </div>
                        </div>
                    </#if>

                    <div class="${properties.kcFormGroupClass!}">
                        <label for="password" class="${properties.kcLabelClass!} eigen-label">
                            Mot de passe
                        </label>
                        <div class="eigen-input-group">
                            <span class="eigen-input-icon">🔒</span>
                            <input tabindex="2" id="password"
                                   class="${properties.kcInputClass!} eigen-input"
                                   name="password" type="password"
                                   placeholder="Mot de passe"
                                   autocomplete="off"/>
                            <button type="button" class="eigen-pwd-toggle"
                                    onclick="togglePwd()" aria-label="Afficher le mot de passe">👁</button>
                        </div>
                    </div>

                    <#if realm.resetPasswordAllowed>
                        <div class="eigen-forgot">
                            <a href="${url.loginResetCredentialsUrl}">Mot de passe oublié ?</a>
                        </div>
                    </#if>

                    <div class="${properties.kcFormGroupClass!}">
                        <input type="hidden" id="id-hidden-input" name="credentialId"
                               <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                        <input tabindex="3" class="eigen-btn" name="login" id="kc-login"
                               type="submit" value="Se connecter à ${realm.displayName?replace("EIGEN — ", "")}"/>
                    </div>

                    <div class="eigen-secure-badge">
                        🔐 Connexion sécurisée — Données protégées par EIGEN
                    </div>
                </form>
            </#if>

            <#if realm.password && social.providers??>
                <div id="kc-social-providers">
                    <div class="eigen-sso-divider">
                        <span>Ou se connecter via</span>
                    </div>
                    <ul class="${properties.kcFormSocialAccountListClass!}">
                        <#list social.providers as p>
                            <li>
                                <a id="social-${p.alias}" class="eigen-sso-btn" href="${p.loginUrl}">
                                    🌐 ${p.displayName!}
                                </a>
                            </li>
                        </#list>
                    </ul>
                </div>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>

<script>
function togglePwd() {
    const p = document.getElementById('password');
    p.type = p.type === 'password' ? 'text' : 'password';
}
</script>
