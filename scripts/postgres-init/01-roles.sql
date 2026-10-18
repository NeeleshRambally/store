-- Runtime role for the store service: connect, read and insert only; the schema is owned and migrated by admin.
CREATE ROLE store_app LOGIN PASSWORD 'store_app';
GRANT CONNECT ON DATABASE store TO store_app;
GRANT USAGE ON SCHEMA public TO store_app;
ALTER DEFAULT PRIVILEGES FOR ROLE admin IN SCHEMA public GRANT SELECT, INSERT ON TABLES TO store_app;
ALTER DEFAULT PRIVILEGES FOR ROLE admin IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO store_app;
