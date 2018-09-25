# Try to install the schema until it works.
while ! mysql --password=$DB_PASSWD < /schema/schema.sql; do
    echo Failed to connect to database. Trying again.
    sleep 1
done

echo Installed schema. Sleeping forever
while true; do
    sleep 1
done
