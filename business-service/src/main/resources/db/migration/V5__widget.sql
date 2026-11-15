-- Website chat widget settings per business (T38, public conversation API).
-- publishable_key: starts a widget conversation. Public by design (it is in the page's HTML),
-- so it is stored as is; allowed domains and rate limits protect it.
-- widget_settings: {"allowedDomains": ["https://shop.example.com"], "color": "#...", "position": "..."}.
-- allowedDomains holds origins (scheme, host and port), compared with the request's Origin header.

ALTER TABLE core.businesses
    ADD COLUMN publishable_key VARCHAR(64) UNIQUE,
    ADD COLUMN widget_settings JSONB NOT NULL DEFAULT '{}';

-- The demo businesses accept the local web app (Vite dev server and the compose nginx, both :5173).
UPDATE core.businesses
SET publishable_key = 'pk_demo_joes_pizza',
    widget_settings = '{"allowedDomains": ["http://localhost:5173", "http://127.0.0.1:5173"]}'
WHERE slug = 'joes-pizza';

UPDATE core.businesses
SET publishable_key = 'pk_demo_denim_corner',
    widget_settings = '{"allowedDomains": ["http://localhost:5173", "http://127.0.0.1:5173"]}'
WHERE slug = 'denim-corner';

-- Any other business gets a random key and no allowed domains (the widget stays closed).
UPDATE core.businesses
SET publishable_key = 'pk_' || replace(gen_random_uuid()::text, '-', '')
WHERE publishable_key IS NULL;

ALTER TABLE core.businesses ALTER COLUMN publishable_key SET NOT NULL;
