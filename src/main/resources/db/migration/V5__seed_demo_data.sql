INSERT INTO specialty (name, description) VALUES
    ('Family Medicine', 'Primary and preventive care for patients of all ages.'),
    ('Cardiology', 'Evaluation and treatment of heart and circulatory conditions.'),
    ('Dermatology', 'Diagnosis and treatment of skin, hair, and nail conditions.');

INSERT INTO appointment_type (
    name,
    duration_minutes,
    specialty_id,
    description,
    is_active
) VALUES
    (
        'Family Medicine New Patient Visit',
        60,
        (SELECT id FROM specialty WHERE name = 'Family Medicine'),
        'Comprehensive first visit with a family medicine provider.',
        true
    ),
    (
        'Family Medicine Follow-up',
        30,
        (SELECT id FROM specialty WHERE name = 'Family Medicine'),
        'Follow-up visit for an established family medicine patient.',
        true
    ),
    (
        'Cardiology Consultation',
        60,
        (SELECT id FROM specialty WHERE name = 'Cardiology'),
        'Initial cardiology consultation and evaluation.',
        true
    ),
    (
        'Cardiology Follow-up',
        30,
        (SELECT id FROM specialty WHERE name = 'Cardiology'),
        'Follow-up visit for an established cardiology patient.',
        true
    ),
    (
        'Dermatology Consultation',
        45,
        (SELECT id FROM specialty WHERE name = 'Dermatology'),
        'Initial dermatology consultation and skin evaluation.',
        true
    ),
    (
        'Dermatology Follow-up',
        30,
        (SELECT id FROM specialty WHERE name = 'Dermatology'),
        'Follow-up visit for an established dermatology patient.',
        true
    );

INSERT INTO users (email, password_hash, role, display_name) VALUES
    (
        'demo.patient1@example.com',
        '$2a$10$U31y.GNNWuWHsqM3g7AXlOmcZSQUXRCB6srA96T4WqGnBzI.bFfmW',
        'PATIENT',
        'Demo Patient One'
    ),
    (
        'demo.patient2@example.com',
        '$2a$10$U31y.GNNWuWHsqM3g7AXlOmcZSQUXRCB6srA96T4WqGnBzI.bFfmW',
        'PATIENT',
        'Demo Patient Two'
    ),
    (
        'demo.provider1@example.com',
        '$2a$10$U31y.GNNWuWHsqM3g7AXlOmcZSQUXRCB6srA96T4WqGnBzI.bFfmW',
        'PROVIDER',
        'Dr. Demo Provider One'
    ),
    (
        'demo.provider2@example.com',
        '$2a$10$U31y.GNNWuWHsqM3g7AXlOmcZSQUXRCB6srA96T4WqGnBzI.bFfmW',
        'PROVIDER',
        'Dr. Demo Provider Two'
    ),
    (
        'demo.provider3@example.com',
        '$2a$10$U31y.GNNWuWHsqM3g7AXlOmcZSQUXRCB6srA96T4WqGnBzI.bFfmW',
        'PROVIDER',
        'Dr. Demo Provider Three'
    ),
    (
        'demo.receptionist@example.com',
        '$2a$10$U31y.GNNWuWHsqM3g7AXlOmcZSQUXRCB6srA96T4WqGnBzI.bFfmW',
        'RECEPTIONIST',
        'Demo Receptionist'
    ),
    (
        'demo.admin@example.com',
        '$2a$10$U31y.GNNWuWHsqM3g7AXlOmcZSQUXRCB6srA96T4WqGnBzI.bFfmW',
        'ADMIN',
        'Demo Administrator'
    );

INSERT INTO provider (user_id, specialty_id, license_number, qualifications) VALUES
    (
        (SELECT id FROM users WHERE email = 'demo.provider1@example.com'),
        (SELECT id FROM specialty WHERE name = 'Family Medicine'),
        'DEMO-LICENSE-001',
        'Demo family medicine provider'
    ),
    (
        (SELECT id FROM users WHERE email = 'demo.provider2@example.com'),
        (SELECT id FROM specialty WHERE name = 'Cardiology'),
        'DEMO-LICENSE-002',
        'Demo cardiology provider'
    ),
    (
        (SELECT id FROM users WHERE email = 'demo.provider3@example.com'),
        (SELECT id FROM specialty WHERE name = 'Dermatology'),
        'DEMO-LICENSE-003',
        'Demo dermatology provider'
    );

INSERT INTO provider_schedule (provider_id, day_of_week, start_time, end_time, is_active) VALUES
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider1@example.com'), 'MONDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider1@example.com'), 'TUESDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider1@example.com'), 'WEDNESDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider1@example.com'), 'THURSDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider1@example.com'), 'FRIDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider2@example.com'), 'MONDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider2@example.com'), 'TUESDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider2@example.com'), 'WEDNESDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider2@example.com'), 'THURSDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider2@example.com'), 'FRIDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider3@example.com'), 'MONDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider3@example.com'), 'TUESDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider3@example.com'), 'WEDNESDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider3@example.com'), 'THURSDAY', '09:00', '17:00', true),
    ((SELECT p.id FROM provider p JOIN users u ON u.id = p.user_id
        WHERE u.email = 'demo.provider3@example.com'), 'FRIDAY', '09:00', '17:00', true);
