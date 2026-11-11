{
  username = "thomas";
  fullName = "Thomas Marchand";
  email = "thomas@lfglabs.dev";
  defaultTimeZone = "Europe/Lisbon";

  hindsight = {
    apiUrl = "https://memory.thomas.md";
    bank = "thomas";
    notesDir = "/home/thomas/Notes";
  };

  elsewhenCities = [
    {
      name = "Lisbon";
      zone = "Europe/Lisbon";
      lat = 38.72509;
      lon = -9.1498;
      region = "Lisbon District, PT";
    }
    {
      name = "Paris";
      zone = "Europe/Paris";
    }
    {
      name = "San Francisco";
      zone = "America/Los_Angeles";
      lat = 37.77493;
      lon = -122.41942;
      region = "California, US";
    }
  ];

  sshKeys = rec {
    yubikeyAdmin = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBE+u67Yyt9LJzvAPclO77RanVelzbQCFVxBn1JNRbqDUOaxpi2zNjarsektqkqeRgE1f6SjQErlg14+cPahyJdI= thomas-yubikey-admin";
    breakglass = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAtlSyHA8nrrj3Uv+MaylUxp8VvgTxE6PQUOzkG4AnHt thomas-breakglass";
    palomaMacbookAir = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJc0SQKxLSh5OBhIrOp7+YpmSnGVvkO7l/hOaC5Klinx paloma-macbook-air@10.88.0.100";
    sparkDebug = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOaAvcqsYHGjxyUIb8odmePvJT26SjXl05seGZVAauhQ spark-admin@m2air-debug";

    admin = [
      yubikeyAdmin
      breakglass
    ];

    m2airAuthorized = admin ++ [
      palomaMacbookAir
      sparkDebug
    ];
  };
}
