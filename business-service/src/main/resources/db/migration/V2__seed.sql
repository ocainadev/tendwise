-- Demo data: 2 businesses, 30 catalog items, 20 orders.
-- All names and phone numbers are fictional (US numbers use the 555-01xx range reserved for fiction).

INSERT INTO core.businesses (slug, name, category, default_language, timezone, opening_hours) VALUES
('joes-pizza', 'Joe''s Pizza', 'restaurant', 'en', 'America/New_York',
 '{"mon": [], "tue": [{"open": "11:00", "close": "22:00"}], "wed": [{"open": "11:00", "close": "22:00"}],
   "thu": [{"open": "11:00", "close": "22:00"}], "fri": [{"open": "11:00", "close": "23:30"}],
   "sat": [{"open": "12:00", "close": "23:30"}], "sun": [{"open": "12:00", "close": "21:00"}]}'),
('denim-corner', 'Denim Corner', 'clothing_store', 'pt', 'America/Sao_Paulo',
 '{"mon": [{"open": "09:00", "close": "19:00"}], "tue": [{"open": "09:00", "close": "19:00"}],
   "wed": [{"open": "09:00", "close": "19:00"}], "thu": [{"open": "09:00", "close": "19:00"}],
   "fri": [{"open": "09:00", "close": "20:00"}], "sat": [{"open": "10:00", "close": "18:00"}], "sun": []}');

-- Joe's Pizza: 15 items (USD)
INSERT INTO core.catalog_items (business_id, name, description, price, currency, available, kind)
SELECT b.id, v.name, v.description, v.price, 'USD', v.available, v.kind
FROM core.businesses b,
(VALUES
  ('Margherita', 'Tomato sauce, mozzarella, fresh basil. 12-inch.', 14.00, TRUE, 'PRODUCT'),
  ('Pepperoni', 'Tomato sauce, mozzarella, pepperoni. 12-inch.', 16.00, TRUE, 'PRODUCT'),
  ('Four Cheese', 'Mozzarella, gorgonzola, parmesan, provolone. 12-inch.', 17.50, TRUE, 'PRODUCT'),
  ('Veggie Supreme', 'Peppers, onions, mushrooms, olives, tomato. 12-inch.', 16.50, TRUE, 'PRODUCT'),
  ('BBQ Chicken', 'BBQ sauce, grilled chicken, red onion, cilantro. 12-inch.', 18.00, TRUE, 'PRODUCT'),
  ('Hawaiian', 'Tomato sauce, mozzarella, ham, pineapple. 12-inch.', 16.00, FALSE, 'PRODUCT'),
  ('Gluten-Free Margherita', 'Margherita on a gluten-free crust. 10-inch.', 16.00, TRUE, 'PRODUCT'),
  ('Garlic Knots (6)', 'Baked knots with garlic butter and parsley.', 6.50, TRUE, 'PRODUCT'),
  ('Caesar Salad', 'Romaine, parmesan, croutons, Caesar dressing.', 9.00, TRUE, 'PRODUCT'),
  ('Chicken Wings (8)', 'Buffalo or BBQ, with ranch dip.', 12.00, TRUE, 'PRODUCT'),
  ('Tiramisu', 'House-made, one slice.', 7.00, TRUE, 'PRODUCT'),
  ('Cannoli (2)', 'Ricotta and chocolate chip filling.', 6.00, TRUE, 'PRODUCT'),
  ('Soda Can', 'Cola, diet cola, lemon-lime or orange.', 2.00, TRUE, 'PRODUCT'),
  ('Sparkling Water', '500 ml bottle.', 2.50, TRUE, 'PRODUCT'),
  ('Party Catering (20 people)', 'Five large pizzas, salad and dessert, delivered and set up.', 180.00, TRUE, 'SERVICE')
) AS v(name, description, price, available, kind)
WHERE b.slug = 'joes-pizza';

-- Denim Corner: 15 items (BRL)
INSERT INTO core.catalog_items (business_id, name, description, price, currency, available, kind)
SELECT b.id, v.name, v.description, v.price, 'BRL', v.available, v.kind
FROM core.businesses b,
(VALUES
  ('Slim Fit Jeans', 'Dark wash, stretch denim. Sizes 36-48.', 199.90, TRUE, 'PRODUCT'),
  ('Straight Leg Jeans', 'Medium wash, 100% cotton. Sizes 36-50.', 189.90, TRUE, 'PRODUCT'),
  ('Mom Jeans', 'High waist, light wash. Sizes 34-46.', 209.90, TRUE, 'PRODUCT'),
  ('Wide Leg Jeans', 'High waist, black. Sizes 34-46.', 219.90, FALSE, 'PRODUCT'),
  ('Denim Jacket', 'Classic trucker jacket, medium wash. Sizes P-GG.', 279.90, TRUE, 'PRODUCT'),
  ('Denim Shorts', 'Light wash, frayed hem. Sizes 34-46.', 129.90, TRUE, 'PRODUCT'),
  ('Denim Skirt', 'A-line midi skirt, dark wash. Sizes 34-44.', 159.90, TRUE, 'PRODUCT'),
  ('Basic White T-Shirt', '100% cotton, crew neck. Sizes P-GG.', 59.90, TRUE, 'PRODUCT'),
  ('Basic Black T-Shirt', '100% cotton, crew neck. Sizes P-GG.', 59.90, TRUE, 'PRODUCT'),
  ('Flannel Shirt', 'Red and black check. Sizes P-GG.', 149.90, TRUE, 'PRODUCT'),
  ('Leather Belt', 'Brown genuine leather, 3.5 cm.', 89.90, TRUE, 'PRODUCT'),
  ('Canvas Tote Bag', 'Natural canvas with denim pocket.', 69.90, TRUE, 'PRODUCT'),
  ('Hem Adjustment', 'Jeans hemming, ready in 2 business days.', 25.00, TRUE, 'SERVICE'),
  ('Waist Adjustment', 'Jeans waist taking-in, ready in 3 business days.', 45.00, TRUE, 'SERVICE'),
  ('Gift Wrapping', 'Gift box with ribbon and card.', 10.00, TRUE, 'SERVICE')
) AS v(name, description, price, available, kind)
WHERE b.slug = 'denim-corner';

-- Joe's Pizza: 10 orders (JP-10xx). ETAs relative to migration time so the demo looks live.
INSERT INTO core.orders (code, business_id, customer_name, customer_phone, fulfilment, status, eta, created_at, updated_at)
SELECT v.code, b.id, v.customer_name, v.customer_phone, v.fulfilment, v.status,
       now() + v.eta_offset, now() - v.age, now() - v.age / 2
FROM core.businesses b,
(VALUES
  ('JP-1041', 'Alex Morgan',   '+1 212-555-0101', 'DELIVERY', 'RECEIVED',         INTERVAL '45 minutes', INTERVAL '2 minutes'),
  ('JP-1042', 'Sam Rivera',    '+1 212-555-0102', 'DELIVERY', 'IN_PROGRESS',      INTERVAL '30 minutes', INTERVAL '15 minutes'),
  ('JP-1043', 'Jordan Lee',    '+1 212-555-0103', 'DELIVERY', 'OUT_FOR_DELIVERY', INTERVAL '10 minutes', INTERVAL '35 minutes'),
  ('JP-1044', 'Taylor Brooks', '+1 212-555-0104', 'PICKUP',   'READY',            INTERVAL '0 minutes',  INTERVAL '25 minutes'),
  ('JP-1045', 'Casey Kim',     '+1 212-555-0105', 'PICKUP',   'IN_PROGRESS',      INTERVAL '15 minutes', INTERVAL '10 minutes'),
  ('JP-1046', 'Morgan Diaz',   '+1 212-555-0106', 'DELIVERY', 'COMPLETED',        INTERVAL '-1 hour',    INTERVAL '2 hours'),
  ('JP-1047', 'Riley Chen',    '+1 212-555-0107', 'DELIVERY', 'CANCELLED',        INTERVAL '-3 hours',   INTERVAL '4 hours'),
  ('JP-1048', 'Jamie Patel',   '+1 212-555-0108', 'IN_STORE', 'COMPLETED',        INTERVAL '-30 minutes',INTERVAL '1 hour'),
  ('JP-1049', 'Drew Santos',   '+1 212-555-0109', 'DELIVERY', 'RECEIVED',         INTERVAL '50 minutes', INTERVAL '1 minute'),
  ('JP-1050', 'Quinn Murphy',  '+1 212-555-0110', 'PICKUP',   'READY',            INTERVAL '0 minutes',  INTERVAL '20 minutes')
) AS v(code, customer_name, customer_phone, fulfilment, status, eta_offset, age)
WHERE b.slug = 'joes-pizza';

-- Denim Corner: 10 orders (DC-20xx)
INSERT INTO core.orders (code, business_id, customer_name, customer_phone, fulfilment, status, eta, created_at, updated_at)
SELECT v.code, b.id, v.customer_name, v.customer_phone, v.fulfilment, v.status,
       now() + v.eta_offset, now() - v.age, now() - v.age / 2
FROM core.businesses b,
(VALUES
  ('DC-2001', 'Ana Souza',      '+55 11 90000-0201', 'DELIVERY', 'RECEIVED',         INTERVAL '3 days',   INTERVAL '1 hour'),
  ('DC-2002', 'Bruno Lima',     '+55 11 90000-0202', 'DELIVERY', 'IN_PROGRESS',      INTERVAL '2 days',   INTERVAL '1 day'),
  ('DC-2003', 'Carla Mendes',   '+55 11 90000-0203', 'DELIVERY', 'OUT_FOR_DELIVERY', INTERVAL '4 hours',  INTERVAL '2 days'),
  ('DC-2004', 'Diego Alves',    '+55 21 90000-0204', 'PICKUP',   'READY',            INTERVAL '0 hours',  INTERVAL '1 day'),
  ('DC-2005', 'Elisa Rocha',    '+55 11 90000-0205', 'IN_STORE', 'IN_PROGRESS',      INTERVAL '2 days',   INTERVAL '1 day'),
  ('DC-2006', 'Felipe Costa',   '+55 31 90000-0206', 'DELIVERY', 'COMPLETED',        INTERVAL '-2 days',  INTERVAL '6 days'),
  ('DC-2007', 'Gabriela Nunes', '+55 11 90000-0207', 'DELIVERY', 'CANCELLED',        INTERVAL '-1 day',   INTERVAL '3 days'),
  ('DC-2008', 'Hugo Martins',   '+55 11 90000-0208', 'PICKUP',   'COMPLETED',        INTERVAL '-3 days',  INTERVAL '5 days'),
  ('DC-2009', 'Isabela Ferraz', '+55 41 90000-0209', 'DELIVERY', 'RECEIVED',         INTERVAL '4 days',   INTERVAL '30 minutes'),
  ('DC-2010', 'João Pereira',   '+55 11 90000-0210', 'IN_STORE', 'READY',            INTERVAL '0 hours',  INTERVAL '3 days')
) AS v(code, customer_name, customer_phone, fulfilment, status, eta_offset, age)
WHERE b.slug = 'denim-corner';
