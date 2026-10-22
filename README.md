# Advanced All-in-One AI & Microservices Platform

A robust multi-service enterprise platform built with Spring Boot, Spring Cloud, Netflix Eureka, H2, and React.

## System Architecture

The platform follows a modular microservices architecture orchestrated via an API Gateway and Service Discovery:

- **API Gateway (`gateway` - Port 8080)**: Reverse proxy routing `/api/**` requests to downstream microservices.
- **Service Discovery (`discovery-service` - Port 8761)**: Spring Cloud Netflix Eureka registry for service discovery and load balancing.
- **Auth & User Service (`auth-service` - Port 8081)**: User accounts, JWT authentication, roles (Student, Teacher, Buyer, Seller), activity logging, and profile photo upload/retrieval.
- **Product Catalog Service (`catalog-service` - Port 8082)**: Marketplace product listings, categories, inventory, and product image management.
- **Shopping Cart & Orders Service (`cart-service` - Port 8083)**: Cart items, checkout, order placement, invoices, and seller order fulfillment tracking.
- **Classroom & Learning Service (`classroom-service` - Port 8084)**: Modules, topics, video lessons (stored in separate video package & storage directory), downloadable/text study materials, quizzes, student private notes, and public threaded discussions with commenter profile photos and date & time stamps.
- **Frontend UI (`UI/play`)**: React single-page application communicating with the API Gateway.

## Key Features

- **Classroom Comments & Discussions**: Commenters' profile photos and comment date & time are displayed alongside their names in all discussion threads and replies.
- **In-Player Video Speed Control**: Adjustable video playback speed (0.75x to 2.75x) embedded directly as an overlay within the video player.
- **Modular Video Storage**: Dedicated `video` package (`com.example.dashboard.classroom.video`) and isolated storage directory (`resources/classroom/videos/`) for uploaded video lessons.
- **Multi-Role Workspace**: Support for Student, Teacher, Buyer, and Seller roles with dedicated studios and workflows.

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

## License
Project maintained by Ramkrishna97.
