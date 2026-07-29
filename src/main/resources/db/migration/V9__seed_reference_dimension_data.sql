-- Reference dimension data so later phases (and this phase's own tests) have
-- something to join against before the real import pipeline exists (Phase 3).
--
-- Regions, attack types, weapon types, and target types are small, fixed,
-- well-known enumerations under the real GTD Codebook, so the FULL canonical
-- set is seeded for each (not an arbitrary subset) - there is no meaningful
-- "handful" of attack types short of the real list. Countries and
-- organizations are open-ended/large domains (~200 countries, thousands of
-- orgs in the real dataset), so only a representative handful is seeded here,
-- spanning every region; Phase 3's import normalizes the rest from the real
-- CSV. See CLAUDE.md section 12 for this decision recorded in full.
--
-- Every dimension table's is_unknown=TRUE row is seeded here too, satisfying
-- the partial unique indexes created in V2.

-- ---------------------------------------------------------------------
-- Regions (12 canonical GTD regions + Unknown)
-- ---------------------------------------------------------------------
INSERT INTO dim_region (name, is_unknown) VALUES
    ('North America', FALSE),
    ('Central America & Caribbean', FALSE),
    ('South America', FALSE),
    ('East Asia', FALSE),
    ('Southeast Asia', FALSE),
    ('South Asia', FALSE),
    ('Central Asia', FALSE),
    ('Western Europe', FALSE),
    ('Eastern Europe', FALSE),
    ('Middle East & North Africa', FALSE),
    ('Sub-Saharan Africa', FALSE),
    ('Australasia & Oceania', FALSE),
    ('Unknown', TRUE);

-- ---------------------------------------------------------------------
-- Countries (a representative handful spanning every region, + Unknown)
-- ---------------------------------------------------------------------
INSERT INTO dim_country (name, region_id, is_unknown) VALUES
    ('United States', (SELECT id FROM dim_region WHERE name = 'North America'), FALSE),
    ('Canada', (SELECT id FROM dim_region WHERE name = 'North America'), FALSE),
    ('Mexico', (SELECT id FROM dim_region WHERE name = 'Central America & Caribbean'), FALSE),
    ('Colombia', (SELECT id FROM dim_region WHERE name = 'South America'), FALSE),
    ('Brazil', (SELECT id FROM dim_region WHERE name = 'South America'), FALSE),
    ('Peru', (SELECT id FROM dim_region WHERE name = 'South America'), FALSE),
    ('United Kingdom', (SELECT id FROM dim_region WHERE name = 'Western Europe'), FALSE),
    ('Germany', (SELECT id FROM dim_region WHERE name = 'Western Europe'), FALSE),
    ('Spain', (SELECT id FROM dim_region WHERE name = 'Western Europe'), FALSE),
    ('Russia', (SELECT id FROM dim_region WHERE name = 'Eastern Europe'), FALSE),
    ('Ukraine', (SELECT id FROM dim_region WHERE name = 'Eastern Europe'), FALSE),
    ('Iraq', (SELECT id FROM dim_region WHERE name = 'Middle East & North Africa'), FALSE),
    ('Syria', (SELECT id FROM dim_region WHERE name = 'Middle East & North Africa'), FALSE),
    ('Afghanistan', (SELECT id FROM dim_region WHERE name = 'South Asia'), FALSE),
    ('Pakistan', (SELECT id FROM dim_region WHERE name = 'South Asia'), FALSE),
    ('India', (SELECT id FROM dim_region WHERE name = 'South Asia'), FALSE),
    ('China', (SELECT id FROM dim_region WHERE name = 'East Asia'), FALSE),
    ('Japan', (SELECT id FROM dim_region WHERE name = 'East Asia'), FALSE),
    ('Philippines', (SELECT id FROM dim_region WHERE name = 'Southeast Asia'), FALSE),
    ('Indonesia', (SELECT id FROM dim_region WHERE name = 'Southeast Asia'), FALSE),
    ('Kazakhstan', (SELECT id FROM dim_region WHERE name = 'Central Asia'), FALSE),
    ('Nigeria', (SELECT id FROM dim_region WHERE name = 'Sub-Saharan Africa'), FALSE),
    ('Somalia', (SELECT id FROM dim_region WHERE name = 'Sub-Saharan Africa'), FALSE),
    ('Kenya', (SELECT id FROM dim_region WHERE name = 'Sub-Saharan Africa'), FALSE),
    ('Australia', (SELECT id FROM dim_region WHERE name = 'Australasia & Oceania'), FALSE),
    ('Unknown', (SELECT id FROM dim_region WHERE name = 'Unknown'), TRUE);

-- ---------------------------------------------------------------------
-- Attack types (full canonical set of 9)
-- ---------------------------------------------------------------------
INSERT INTO dim_attack_type (name, is_unknown) VALUES
    ('Assassination', FALSE),
    ('Armed Assault', FALSE),
    ('Bombing/Explosion', FALSE),
    ('Hijacking', FALSE),
    ('Hostage Taking (Barricade Incident)', FALSE),
    ('Hostage Taking (Kidnapping)', FALSE),
    ('Facility/Infrastructure Attack', FALSE),
    ('Unarmed Assault', FALSE),
    ('Unknown', TRUE);

-- ---------------------------------------------------------------------
-- Weapon types (full canonical set of 13)
-- ---------------------------------------------------------------------
INSERT INTO dim_weapon_type (name, is_unknown) VALUES
    ('Biological', FALSE),
    ('Chemical', FALSE),
    ('Radiological', FALSE),
    ('Nuclear', FALSE),
    ('Firearms', FALSE),
    ('Explosives', FALSE),
    ('Fake Weapons', FALSE),
    ('Incendiary', FALSE),
    ('Melee', FALSE),
    ('Vehicle', FALSE),
    ('Sabotage Equipment', FALSE),
    ('Other', FALSE),
    ('Unknown', TRUE);

-- ---------------------------------------------------------------------
-- Target types (full canonical set of 22)
-- ---------------------------------------------------------------------
INSERT INTO dim_target_type (name, is_unknown) VALUES
    ('Business', FALSE),
    ('Government (General)', FALSE),
    ('Police', FALSE),
    ('Military', FALSE),
    ('Abortion Related', FALSE),
    ('Airports & Aircraft', FALSE),
    ('Government (Diplomatic)', FALSE),
    ('Educational Institution', FALSE),
    ('Food or Water Supply', FALSE),
    ('Journalists & Media', FALSE),
    ('Maritime', FALSE),
    ('NGO', FALSE),
    ('Other', FALSE),
    ('Private Citizens & Property', FALSE),
    ('Religious Figures/Institutions', FALSE),
    ('Telecommunication', FALSE),
    ('Terrorists/Non-State Militia', FALSE),
    ('Tourists', FALSE),
    ('Transportation', FALSE),
    ('Utilities', FALSE),
    ('Violent Political Party', FALSE),
    ('Unknown', TRUE);

-- ---------------------------------------------------------------------
-- Organizations (a representative handful of well-known GTD gname values,
-- + Unknown - GTD's own convention is literally the string 'Unknown' for
-- unidentified perpetrators, which is what is_unknown=TRUE models here)
-- ---------------------------------------------------------------------
INSERT INTO dim_organization (name, is_unknown) VALUES
    ('Taliban', FALSE),
    ('Islamic State of Iraq and the Levant (ISIL)', FALSE),
    ('Al-Shabaab', FALSE),
    ('Boko Haram', FALSE),
    ('Shining Path (SL)', FALSE),
    ('Revolutionary Armed Forces of Colombia (FARC)', FALSE),
    ('Irish Republican Army (IRA)', FALSE),
    ('Basque Fatherland and Freedom (ETA)', FALSE),
    ('Unknown', TRUE);
