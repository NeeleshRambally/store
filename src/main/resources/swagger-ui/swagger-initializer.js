// Loads the checked-in contract, with no call to the public spec validator.
window.onload = () => {
    window.ui = SwaggerUIBundle({
        url: '/openapi.yaml',
        dom_id: '#swagger-ui',
        deepLinking: true,
        validatorUrl: null,
    });
};
