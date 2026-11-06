# Advanced All-in-One AI & Microservices Platform

Deployed at: [https://aio-app.netlify.app](https://aio-app.netlify.app)

A robust multi-service enterprise platform built with Spring Boot, Spring Cloud, Netflix Eureka, H2, and React.

## System Architecture

The platform follows a modular microservices architecture orchestrated via an API Gateway and Service Discovery:

- **API Gateway (`gateway` - Port 8080)**: Reverse proxy routing `/api/**` requests to downstream microservices.
- **Service Discovery (`discovery-service` - Port 8761)**: Spring Cloud Netflix Eureka registry for service discovery and load balancing.
- **Auth & User Service (`auth-service` - Port 8081)**: User accounts, JWT authentication, roles (Student, Teacher, Buyer, Seller, Admin), activity logging, and profile photo upload/retrieval.
- **Product Catalog Service (`catalog-service` - Port 8082)**: Marketplace product listings, categories, inventory, and product image management.
- **Shopping Cart & Orders Service (`cart-service` - Port 8083)**: Cart items, checkout, order placement, invoices, and seller order fulfillment tracking.
- **Classroom & Learning Service (`classroom-service` - Port 8084)**: Modules, topics, video lessons (stored in separate video package & storage directory), downloadable/text study materials, quizzes, student private notes, and public threaded discussions with commenter profile photos and date & time stamps.
- **Frontend UI (`UI/play`)**: React single-page application communicating with the API Gateway.

## Key Features

- **Classroom Comments & Discussions**: Commenters' profile photos and comment date & time are displayed alongside their names in all discussion threads and replies.
- **In-Player Video Speed Control**: Adjustable video playback speed (0.75x to 2.75x) embedded directly as an overlay within the video player.
- **Modular Video Storage**: Dedicated `video` package (`com.example.dashboard.classroom.video`) and isolated storage directory (`resources/classroom/videos/`) for uploaded video lessons.
- **Multi-Role Workspace**: Support for Student, Teacher, Buyer, Seller, and Admin roles with dedicated studios and workflows.

### Admin access

The auth service creates the admin account on startup. Sign in with username `admin` and password `qwertyuiop@123`. Only an admin can create another admin or assign the admin role. The Admin page can add, edit, and permanently delete users; edit posts, products, quizzes, notes, drafts, classroom materials, modules, topics, carts, orders, and delivery records; and publish posts or products for any user or as generic admin content. Section headings expand and collapse their records. Admin-created posts and products are visibly attributed to admin. Editing an account invalidates its existing sessions; deleting it requires a new registration before that person can sign in again.

## Getting Started

### Prerequisites
- Java 17 Development Kit (JDK)
- Node.js & npm

### Running the Backend
1. Navigate to the `Backend` directory.
2. Run `./gradlew bootRun` (or run individual services via Gradle/Spring Boot).

### Running the Frontend
1. Navigate to `UI/play`.
2. Run `npm install` and `npm start`.

## Resource-constrained deployment

The Render Docker service uses deployment-only limits from `render.yaml`:

- uploaded images are converted to JPEG and reduced to at most 50 KB;
- videos and other uploaded classroom files are limited to 2 MB;
- runtime databases and uploads share a 200 MB application quota and new uploads fail with HTTP 507 when it is full;
- local files under `resources/` are excluded from the Docker build, apart from `default.svg` and the quiz schema.

These limits are enabled by `MEDIA_RESOURCE_LIMITS=true`. Local runs do not set that variable, retain generous upload limits, and continue using the complete local `resources/` folder. Render's container storage should be treated as disposable; use a persistent disk or object storage if deployed uploads must survive a replacement or redeploy.

## License
Project maintained by Ramkrishna97.
