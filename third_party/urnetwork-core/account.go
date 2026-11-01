package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"

	gojwt "github.com/golang-jwt/jwt/v5"

	"github.com/urnetwork/connect"
	"github.com/urnetwork/sdk"
)

func readAccount(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

// writeAccount replaces the token file whole: a reader never sees half a token.
func writeAccount(path string, token string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(token), 0600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// What this process reads out of a network token. Unverified: only to compare two tokens it
// already holds; the server is the authority on both.
type tokenClaims struct {
	network string
	name    string
	issued  time.Time
	expires time.Time
}

func claimsOf(token string) tokenClaims {
	m := gojwt.MapClaims{}
	if _, _, err := gojwt.NewParser().ParseUnverified(token, m); err != nil {
		return tokenClaims{}
	}
	c := tokenClaims{}
	if v, ok := m["network_id"].(string); ok {
		c.network = v
	}
	if v, ok := m["network_name"].(string); ok {
		c.name = v
	}
	if e, err := m.GetExpirationTime(); err == nil && e != nil {
		c.expires = e.Time
	}
	if i, err := m.GetIssuedAt(); err == nil && i != nil {
		c.issued = i.Time
	}
	return c
}

// halfLived: the token is past the middle of its life, when the SDK's own owners renew theirs.
func (self tokenClaims) halfLived(now time.Time) bool {
	if self.issued.IsZero() || !self.issued.Before(self.expires) {
		return false
	}
	return now.After(self.issued.Add(self.expires.Sub(self.issued) / 2))
}

// createAccount makes an instant account: no email, no password, a recovery phrase. Only ever
// run after the user accepted URnetwork's terms on the screen; Terms is sent as that acceptance.
func createAccount(state string, account string) {
	api := openSpace(state).GetApi()
	r := newSyncResult[*sdk.NetworkCreateResult]()
	api.NetworkCreate(&sdk.NetworkCreateArgs{Terms: true}, r)
	res, err := r.wait(90 * time.Second)
	if err != nil {
		fail(exitError, "%s", apiError(err))
	}
	if res == nil {
		fail(exitError, "empty answer")
	}
	if res.Error != nil {
		fail(exitError, "%s", res.Error.Message)
	}
	if res.VerificationRequired != nil {
		fail(exitError, "the server asked for a verification this account type cannot give")
	}
	if res.Network == nil || res.Network.ByJwt == "" {
		fail(exitError, "no account in the answer")
	}
	seed := res.Seedphrase
	if seed == "" {
		// The phrase is the only way back to the account; ask for one if the create did not carry it.
		api.SetByJwt(res.Network.ByJwt)
		g := newSyncResult[*sdk.GenerateSeedphraseResult]()
		api.GenerateSeedphrase(&sdk.GenerateSeedphraseArgs{}, g)
		if gr, err := g.wait(60 * time.Second); err == nil && gr != nil {
			seed = gr.Seedphrase
		}
	}
	if err := writeAccount(account, res.Network.ByJwt); err != nil {
		fail(exitError, "%v", err)
	}
	say("account %s", res.Network.NetworkName)
	if seed != "" {
		say("seed %s", seed)
	}
}

// restoreAccount signs in with the recovery phrase of an account made before.
func restoreAccount(state string, account string, phrase string) {
	if phrase == "" {
		fail(exitError, "empty recovery phrase")
	}
	api := openSpace(state).GetApi()
	r := newSyncResult[*sdk.AuthLoginResult]()
	api.AuthLogin(&sdk.AuthLoginArgs{Seedphrase: phrase}, r)
	res, err := r.wait(90 * time.Second)
	if err != nil {
		fail(exitError, "%s", apiError(err))
	}
	if res == nil {
		fail(exitError, "empty answer")
	}
	if res.Error != nil {
		fail(exitError, "%s", res.Error.Message)
	}
	if res.Network == nil || res.Network.ByJwt == "" {
		fail(exitError, "the recovery phrase was not accepted")
	}
	if err := writeAccount(account, res.Network.ByJwt); err != nil {
		fail(exitError, "%v", err)
	}
	say("account %s", claimsOf(res.Network.ByJwt).name)
}

// callApi makes one read-only call with the account's token and leaves the answer in out.
func callApi(state string, account string, what string, out string) {
	token := readAccount(account)
	if token == "" {
		fail(exitNoAccount, "no account")
	}
	api := openSpace(state).GetApi()
	api.SetByJwt(token)
	// The account's network token lives in the account file only, never in a job's folder, so
	// nothing else renews it: this is its one owner. Renewed past its half-life, as the SDK renews
	// the tokens it owns; a refused renewal is a sign-in that is gone (the app signs in again with
	// the recovery phrase).
	if claimsOf(token).halfLived(time.Now()) {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		res, err := api.NetworkRefreshSyncWithContextAndJwt(ctx, token)
		cancel()
		switch {
		case err == nil && res != nil && res.Error == nil && res.ByJwt != "":
			if writeAccount(account, res.ByJwt) == nil {
				token = res.ByJwt
				api.SetByJwt(token)
				say("account renewed")
			}
		case err != nil && sdk.ConfirmedClientRefreshRejection(err):
			fail(exitLoggedOut, "auth renewal refused")
		}
	}
	var answer any
	switch what {
	case "balance":
		r := newSyncResult[*sdk.SubscriptionBalanceResult]()
		api.SubscriptionBalance(r)
		res, err := r.wait(60 * time.Second)
		if err != nil {
			failApi(err)
		}
		answer = res
	case "locations":
		r := newSyncResult[*sdk.FindLocationsResult]()
		api.GetProviderLocations(r)
		res, err := r.wait(60 * time.Second)
		if err != nil {
			failApi(err)
		}
		answer = res
	default:
		fail(exitError, "unknown -api %s", what)
	}
	b, err := json.Marshal(answer)
	if err != nil {
		fail(exitError, "%v", err)
	}
	tmp := out + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		fail(exitError, "%v", err)
	}
	if err := os.Rename(tmp, out); err != nil {
		fail(exitError, "%v", err)
	}
	say("done %d", len(b))
}

func failApi(err error) {
	if isAuthError(err) {
		fail(exitLoggedOut, "auth %s", apiError(err))
	}
	fail(exitError, "%s", apiError(err))
}

func isAuthError(err error) bool {
	var status *connect.HttpStatusError
	return errors.As(err, &status) && (status.StatusCode == 401 || status.StatusCode == 403)
}

// apiError keeps an HTTP failure short: the status, and the body only when it is small text.
func apiError(err error) string {
	var status *connect.HttpStatusError
	if errors.As(err, &status) {
		body := strings.TrimSpace(string(status.Body))
		if len(body) > 200 || strings.ContainsAny(body, "<{") {
			body = ""
		}
		if body != "" {
			return status.Status + ": " + body
		}
		return status.Status
	}
	return err.Error()
}
